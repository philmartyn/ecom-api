package com.example.ecom.payment;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.CategoryRepository;
import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.common.config.JpaAuditingConfig;
import com.example.ecom.order.Order;
import com.example.ecom.order.OrderRepository;
import com.example.ecom.order.ShippingAddress;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import com.example.ecom.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment persistence against a real PostgreSQL container, so the Flyway DDL is
 * what is under test: the two partial unique indexes, the provider-reference
 * uniqueness, the sweeper query, and the cascade from an Order.
 * <p>
 * The one-open-attempt and one-succeeded-attempt rules are database rules, not
 * service conventions. A failed attempt must not block a retry, while a second
 * open attempt and a second successful attempt on the same Order must be
 * impossible even if a future code path forgets to check.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class PaymentRepositoryTest extends AbstractIntegrationTest {

    private static final ShippingAddress SHIPPING = new ShippingAddress(
            "Bob Smith", "1 Main St", "Apt 2", "Springfield", "12345", "US");

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    private User user;
    private Product product;
    private Order order;

    @BeforeEach
    void setUp() {
        user = userRepository.saveAndFlush(
                new User("bob@example.com", "password-hash", "Bob", "Smith", Role.CUSTOMER));
        Category category = categoryRepository.saveAndFlush(
                new Category("Books", "books", "Paperbacks"));
        product = productRepository.saveAndFlush(new Product(
                category, "SKU-1", "Mechanical Keyboard", "mechanical-keyboard",
                "Clicky keys", new BigDecimal("89.99"), 10, null));

        order = newOrder();
    }

    // --- persistence --------------------------------------------------------

    @Test
    void savesAPendingAttemptWithAuditingAndTheOrderAmount() {
        Payment saved = paymentRepository.saveAndFlush(new Payment(order, "stripe"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getAmount()).isEqualByComparingTo("179.98");
        assertThat(saved.getAmount().scale()).isEqualTo(2);
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getProviderRef()).isNull();
        assertThat(saved.getFailureCode()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findsTheOpenAttemptForAnOrder() {
        Payment pending = paymentRepository.saveAndFlush(new Payment(order, "stripe"));

        assertThat(paymentRepository.findFirstByOrderIdAndStatus(
                order.getId(), PaymentStatus.PENDING))
                .get()
                .extracting(Payment::getId)
                .isEqualTo(pending.getId());
        assertThat(paymentRepository.findFirstByOrderIdAndStatus(
                order.getId(), PaymentStatus.SUCCEEDED))
                .isEmpty();
        assertThat(paymentRepository.existsByOrderIdAndStatus(
                order.getId(), PaymentStatus.PENDING)).isTrue();
    }

    @Test
    void findsAnAttemptByProviderReference() {
        Payment payment = new Payment(order, "stripe");
        payment.attachProviderRef("pi_123");
        paymentRepository.saveAndFlush(payment);

        assertThat(paymentRepository.findByProviderAndProviderRef("stripe", "pi_123"))
                .get()
                .extracting(Payment::getId)
                .isEqualTo(payment.getId());
        assertThat(paymentRepository.findByProviderAndProviderRef("fake", "pi_123"))
                .isEmpty();
    }

    @Test
    void listsAttemptsNewestFirstThenById() {
        Payment failed = new Payment(order, "stripe");
        failed.markFailed("card_declined");
        paymentRepository.saveAndFlush(failed);

        Payment pending = paymentRepository.saveAndFlush(new Payment(order, "stripe"));

        assertThat(paymentRepository.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId()))
                .extracting(Payment::getId)
                .containsExactly(pending.getId(), failed.getId());
    }

    // --- one open attempt ---------------------------------------------------

    @Test
    void oneOpenAttemptPerOrderIsEnforcedByTheDatabase() {
        paymentRepository.saveAndFlush(new Payment(order, "stripe"));

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(new Payment(order, "stripe")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFailedAttemptDoesNotBlockANewOpenAttempt() {
        Payment failed = new Payment(order, "stripe");
        failed.markFailed("card_declined");
        paymentRepository.saveAndFlush(failed);

        Payment pending = paymentRepository.saveAndFlush(new Payment(order, "stripe"));

        assertThat(pending.getId()).isNotNull();
        assertThat(paymentRepository.existsByOrderIdAndStatus(
                order.getId(), PaymentStatus.PENDING)).isTrue();
    }

    // --- one success --------------------------------------------------------

    @Test
    void atMostOneSucceededAttemptPerOrderIsEnforcedByTheDatabase() {
        Payment first = paymentRepository.saveAndFlush(new Payment(order, "stripe"));
        first.markSucceeded();
        paymentRepository.saveAndFlush(first);

        Payment second = new Payment(order, "stripe");
        second.markSucceeded();

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- provider reference uniqueness -------------------------------------

    @Test
    void providerReferenceIsUniquePerProvider() {
        Payment first = new Payment(order, "stripe");
        first.attachProviderRef("pi_123");
        paymentRepository.saveAndFlush(first);

        Payment second = new Payment(newOrder(), "stripe");
        second.attachProviderRef("pi_123");

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameReferenceUnderADifferentProviderIsAllowed() {
        Payment stripe = new Payment(order, "stripe");
        stripe.attachProviderRef("pi_123");
        paymentRepository.saveAndFlush(stripe);

        Payment fake = new Payment(newOrder(), "fake");
        fake.attachProviderRef("pi_123");

        assertThat(paymentRepository.saveAndFlush(fake).getId()).isNotNull();
    }

    @Test
    void nullProviderReferencesAreAllowedForProviderlessAttempts() {
        Payment first = new Payment(order, "none");
        first.markSucceeded();
        paymentRepository.saveAndFlush(first);

        Payment second = new Payment(newOrder(), "none");
        second.markSucceeded();

        assertThat(paymentRepository.saveAndFlush(second).getId()).isNotNull();
        assertThat(first.getProviderRef()).isNull();
        assertThat(second.getProviderRef()).isNull();
    }

    // --- sweeper query ------------------------------------------------------

    @Test
    void theSweeperQueryReturnsOnlyExpiredPendingAttempts() {
        Payment expired = paymentRepository.saveAndFlush(new Payment(order, "stripe"));
        Payment fresh = paymentRepository.saveAndFlush(new Payment(newOrder(), "stripe"));

        Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);
        entityManager.createNativeQuery("""
                        UPDATE payments
                        SET created_at = now() - interval '31 minutes'
                        WHERE id = :id
                        """)
                .setParameter("id", expired.getId())
                .executeUpdate();
        entityManager.clear();

        List<Payment> found = paymentRepository.findExpiredPendingForUpdate(cutoff);

        assertThat(found).extracting(Payment::getId).containsExactly(expired.getId());
        assertThat(found).extracting(Payment::getId).doesNotContain(fresh.getId());
    }

    // --- cascade ------------------------------------------------------------

    @Test
    void deletingAnOrderCascadesToItsAttempts() {
        Payment payment = paymentRepository.saveAndFlush(new Payment(order, "stripe"));
        Long paymentId = payment.getId();
        entityManager.clear();

        orderRepository.delete(order);
        orderRepository.flush();
        entityManager.clear();

        assertThat(paymentRepository.findById(paymentId)).isEmpty();
    }

    // --- fixtures -----------------------------------------------------------

    private Order newOrder() {
        Order created = new Order(user, SHIPPING);
        created.addItem(product, 2);
        return orderRepository.saveAndFlush(created);
    }
}
