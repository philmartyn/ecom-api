package com.example.ecom.payment;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.cart.CartService;
import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.CategoryRepository;
import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.common.exception.OrderUnavailableException;
import com.example.ecom.common.exception.PaymentInProgressException;
import com.example.ecom.common.exception.PaymentNotCancellableException;
import com.example.ecom.common.exception.UnavailableReason;
import com.example.ecom.order.OrderService;
import com.example.ecom.order.ShippingAddress;
import com.example.ecom.payment.gateway.FakePaymentGateway;
import com.example.ecom.payment.gateway.GatewayStatus;
import com.example.ecom.payment.gateway.PaymentGateway;
import com.example.ecom.payment.webhook.VerifiedPaymentEvent;
import com.example.ecom.payment.webhook.WebhookEventType;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The races that justify the reservation design (ADR-0005).
 * <p>
 * There is deliberately no class-level {@code @Transactional}: the whole point
 * is to let two real transactions compete. The fixture is committed in
 * {@code @BeforeEach}, then two threads call the transactional service at the
 * same time.
 * <p>
 * Four guarantees are pinned: only one open attempt per Order, only one winner
 * for the last unit of stock, the sweeper and a success webhook cannot both
 * release and pay, and a cancel/success race always ends with Stripe's success
 * and the stock still reserved.
 */
@SpringBootTest(properties = {
        "app.payments.sweeper.enabled=true",
        "app.payments.sweep-interval=1h"
})
@ActiveProfiles("test")
class PaymentConcurrencyTest extends AbstractIntegrationTest {

    private static final ShippingAddress SHIPPING = new ShippingAddress(
            "Bob Smith", "1 Main St", "Apt 2", "Springfield", "12345", "US");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentReservationSweeper sweeper;

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

    private ExecutorService executor;
    private Long categoryId;
    private Long productId;
    private Long userId;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);

        String suffix = UUID.randomUUID().toString();

        Category category = categoryRepository.saveAndFlush(
                new Category("Races " + suffix, "races-" + suffix, "Concurrency tests"));
        categoryId = category.getId();

        Product product = productRepository.saveAndFlush(new Product(
                category, "SKU-RACE-" + suffix, "Mechanical Keyboard",
                "mechanical-keyboard-" + suffix, null,
                new BigDecimal("89.99"), 10, null));
        productId = product.getId();

        User user = userRepository.saveAndFlush(new User(
                "race-" + suffix + "@example.com", "password-hash",
                "Race", "Test", Role.CUSTOMER));
        userId = user.getId();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();

        if (userId != null) {
            jdbc.update("DELETE FROM orders WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        if (productId != null) {
            jdbc.update("DELETE FROM products WHERE id = ?", productId);
        }
        if (categoryId != null) {
            jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        }
    }

    // --- duplicate creates --------------------------------------------------

    @Test
    void twoConcurrentCreatesOpenExactlyOneAttempt() throws Exception {
        long orderId = newOrder(2);

        CountDownLatch start = new CountDownLatch(1);
        Future<PaymentCreatedResponse> first = createAsync(orderId, start);
        Future<PaymentCreatedResponse> second = createAsync(orderId, start);
        start.countDown();

        int created = 0;
        int inProgress = 0;
        for (Future<PaymentCreatedResponse> future : List.of(first, second)) {
            try {
                assertThat(future.get(10, TimeUnit.SECONDS).status())
                        .isEqualTo(PaymentStatus.PENDING);
                created++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(PaymentInProgressException.class);
                inProgress++;
            }
        }

        assertThat(created).isEqualTo(1);
        assertThat(inProgress).isEqualTo(1);
        assertThat(stock()).isEqualTo(8);
        assertThat(paymentCount(orderId)).isEqualTo(1);
    }

    // --- last unit ----------------------------------------------------------

    @Test
    void theLastUnitIsReservedByExactlyOneOrder() throws Exception {
        jdbc.update("UPDATE products SET stock_quantity = 1 WHERE id = ?", productId);
        long firstOrder = newOrder(1);
        long secondOrder = newOrder(1);

        CountDownLatch start = new CountDownLatch(1);
        Future<PaymentCreatedResponse> first = createAsync(firstOrder, start);
        Future<PaymentCreatedResponse> second = createAsync(secondOrder, start);
        start.countDown();

        int reserved = 0;
        int unavailable = 0;
        for (Future<PaymentCreatedResponse> future : List.of(first, second)) {
            try {
                future.get(10, TimeUnit.SECONDS);
                reserved++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOfSatisfying(
                        OrderUnavailableException.class,
                        ex -> assertThat(ex.getUnavailableItems()).singleElement()
                                .satisfies(item -> assertThat(item.reason())
                                        .isEqualTo(UnavailableReason.INSUFFICIENT_STOCK)));
                unavailable++;
            }
        }

        assertThat(reserved).isEqualTo(1);
        assertThat(unavailable).isEqualTo(1);
        assertThat(stock()).isZero();
        assertThat(paymentCount(firstOrder) + paymentCount(secondOrder)).isEqualTo(1);
    }

    // --- sweeper versus webhook --------------------------------------------

    @Test
    void theSweeperAndASucceededWebhookCannotBothReleaseAndPay() throws Exception {
        jdbc.update("UPDATE products SET stock_quantity = 1 WHERE id = ?", productId);
        long orderId = newOrder(1);
        PaymentCreatedResponse created = paymentService.create(userId, orderId);
        long paymentId = created.id();
        String providerRef = providerRef(paymentId);
        expire(paymentId);

        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> sweep = executor.submit(() -> {
            start.await();
            return sweeper.sweep();
        });
        Future<Void> webhook = executor.submit(() -> {
            start.await();
            paymentService.handleWebhook(new VerifiedPaymentEvent(
                    providerRef, WebhookEventType.PAYMENT_SUCCEEDED, null));
            return null;
        });
        start.countDown();

        sweep.get(10, TimeUnit.SECONDS);
        webhook.get(10, TimeUnit.SECONDS);

        String order = orderStatus(orderId);
        String payment = paymentStatus(paymentId);

        if ("PAID".equals(order)) {
            assertThat(payment).isEqualTo("SUCCEEDED");
            assertThat(stock()).isZero();
        } else {
            assertThat(order).isEqualTo("PENDING");
            assertThat(payment).isEqualTo("FAILED");
            assertThat(stock()).isEqualTo(1);
        }
    }

    // --- cancel versus success ---------------------------------------------

    @Test
    void cancelAndSuccessLeaveStripeInCharge() throws Exception {
        jdbc.update("UPDATE products SET stock_quantity = 1 WHERE id = ?", productId);
        long orderId = newOrder(1);
        PaymentCreatedResponse created = paymentService.create(userId, orderId);
        long paymentId = created.id();
        String providerRef = providerRef(paymentId);
        fakeGateway().setStatus(providerRef, GatewayStatus.SUCCEEDED);

        CountDownLatch start = new CountDownLatch(1);
        Future<PaymentResponse> cancel = executor.submit(() -> {
            start.await();
            return paymentService.cancel(userId, orderId, paymentId);
        });
        Future<Void> webhook = executor.submit(() -> {
            start.await();
            paymentService.handleWebhook(new VerifiedPaymentEvent(
                    providerRef, WebhookEventType.PAYMENT_SUCCEEDED, null));
            return null;
        });
        start.countDown();

        try {
            cancel.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(PaymentNotCancellableException.class);
        }
        webhook.get(10, TimeUnit.SECONDS);

        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(stock()).isZero();
    }

    // --- helpers ------------------------------------------------------------

    private long newOrder(int quantity) {
        cartService.addItem(userId, productId, quantity);
        return orderService.checkout(userId, SHIPPING).id();
    }

    private Future<PaymentCreatedResponse> createAsync(long orderId, CountDownLatch start) {
        return executor.submit(() -> {
            start.await();
            return paymentService.create(userId, orderId);
        });
    }

    private void expire(long paymentId) {
        jdbc.update("""
                UPDATE payments
                SET created_at = now() - interval '31 minutes'
                WHERE id = ?
                """, paymentId);
    }

    private int stock() {
        return jdbc.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class, productId);
    }

    private long paymentCount(long orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE order_id = ?", Long.class, orderId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private String paymentStatus(long paymentId) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String providerRef(long paymentId) {
        return jdbc.queryForObject(
                "SELECT provider_ref FROM payments WHERE id = ?", String.class, paymentId);
    }

    private FakePaymentGateway fakeGateway() {
        return (FakePaymentGateway) paymentGateway;
    }
}
