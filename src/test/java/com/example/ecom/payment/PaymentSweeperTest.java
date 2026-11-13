package com.example.ecom.payment;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.cart.CartService;
import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.CategoryRepository;
import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.order.OrderService;
import com.example.ecom.order.ShippingAddress;
import com.example.ecom.payment.gateway.FakePaymentGateway;
import com.example.ecom.payment.gateway.GatewayStatus;
import com.example.ecom.payment.gateway.PaymentGateway;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import com.example.ecom.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reservation sweeper, called directly rather than waiting 60 seconds.
 * <p>
 * The sweeper is both the expirer and the reconciliation backstop: an expired
 * attempt whose Stripe intent already succeeded must become {@code PAID}, not a
 * release; one that Stripe still allows cancelling is released; and a
 * {@code processing} intent or an unreachable provider is left for the next
 * run. Releasing twice would be an oversell (the stock was already returned),
 * so idempotence is the central property.
 * <p>
 * There is no class-level {@code @Transactional}: the sweeper must run in its
 * own transactions and commit what it changes. The fixture is removed
 * afterwards.
 */
@SpringBootTest(properties = {
        "app.payments.sweeper.enabled=true",
        "app.payments.sweep-interval=1h"
})
@ActiveProfiles("test")
class PaymentSweeperTest extends AbstractIntegrationTest {

    private static final ShippingAddress SHIPPING = new ShippingAddress(
            "Bob Smith", "1 Main St", "Apt 2", "Springfield", "12345", "US");

    @Autowired
    private PaymentReservationSweeper sweeper;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartService cartService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PaymentGateway paymentGateway;

    @Autowired
    private JdbcTemplate jdbc;

    private Long categoryId;
    private Long productId;
    private Long userId;
    private Long orderId;
    private Long paymentId;
    private String paymentRef;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString();

        Category category = categoryRepository.saveAndFlush(
                new Category("Sweeper " + suffix, "sweeper-" + suffix, "Sweeper tests"));
        categoryId = category.getId();

        Product product = productRepository.saveAndFlush(new Product(
                category, "SKU-SWEEP-" + suffix, "Mechanical Keyboard",
                "mechanical-keyboard-" + suffix, null,
                new BigDecimal("89.99"), 10, null));
        productId = product.getId();

        User user = userRepository.saveAndFlush(new User(
                "sweeper-" + suffix + "@example.com", "password-hash",
                "Sweeper", "Test", Role.CUSTOMER));
        userId = user.getId();

        cartService.addItem(userId, productId, 2);
        orderId = orderService.checkout(userId, SHIPPING).id();

        PaymentCreatedResponse created = paymentService.create(userId, orderId);
        paymentId = created.id();
        paymentRef = jdbc.queryForObject(
                "SELECT provider_ref FROM payments WHERE id = ?", String.class, paymentId);
    }

    @AfterEach
    void cleanUp() {
        if (orderId != null) {
            jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        }
        if (userId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        if (productId != null) {
            jdbc.update("DELETE FROM products WHERE id = ?", productId);
        }
        if (categoryId != null) {
            jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        }
    }

    // --- release ------------------------------------------------------------

    @Test
    void releasesAnExpiredAttemptThatStripeHasNotSucceeded() {
        expire();

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(paymentStatus()).isEqualTo("FAILED");
        assertThat(orderStatus()).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(10);
        assertThat(failureCode()).isNull();
    }

    @Test
    void sweepingTwiceDoesNotReleaseTwice() {
        expire();

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(sweeper.sweep()).isZero();

        assertThat(stock()).isEqualTo(10);
        assertThat(paymentStatus()).isEqualTo("FAILED");
    }

    @Test
    void leavesAFreshAttemptAlone() {
        assertThat(sweeper.sweep()).isZero();

        assertThat(paymentStatus()).isEqualTo("PENDING");
        assertThat(orderStatus()).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(8);
    }

    // --- reconciliation -----------------------------------------------------

    @Test
    void finalisesAnExpiredAttemptThatStripeAlreadySucceeded() {
        fakeGateway().setStatus(paymentRef, GatewayStatus.SUCCEEDED);
        expire();

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void leavesAProcessingAttemptForTheNextSweep() {
        fakeGateway().setStatus(paymentRef, GatewayStatus.PROCESSING);
        expire();

        assertThat(sweeper.sweep()).isZero();

        assertThat(paymentStatus()).isEqualTo("PENDING");
        assertThat(orderStatus()).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void leavesTheAttemptAloneWhenTheProviderCannotBeReached() {
        fakeGateway().failNextRetrieve();
        expire();

        assertThat(sweeper.sweep()).isZero();

        assertThat(paymentStatus()).isEqualTo("PENDING");
        assertThat(orderStatus()).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void leavesTheAttemptAloneWhenTheCancelFails() {
        fakeGateway().failNextCancel();
        expire();

        assertThat(sweeper.sweep()).isZero();

        assertThat(paymentStatus()).isEqualTo("PENDING");
        assertThat(orderStatus()).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(8);
    }

    // --- helpers ------------------------------------------------------------

    private void expire() {
        jdbc.update("""
                UPDATE payments
                SET created_at = now() - interval '31 minutes'
                WHERE id = ?
                """, paymentId);
    }

    private String paymentStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String orderStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private String failureCode() {
        return jdbc.queryForObject(
                "SELECT failure_code FROM payments WHERE id = ?", String.class, paymentId);
    }

    private int stock() {
        return jdbc.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class, productId);
    }

    private FakePaymentGateway fakeGateway() {
        return (FakePaymentGateway) paymentGateway;
    }
}
