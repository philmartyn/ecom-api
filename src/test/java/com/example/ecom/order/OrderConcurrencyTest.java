package com.example.ecom.order;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.cart.CartService;
import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.CategoryRepository;
import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.common.exception.EmptyCartException;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import com.example.ecom.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * Proves the guarantees behind the locking decisions in ADR-0004.
 * <p>
 * There is deliberately no class-level {@code @Transactional}: the whole point
 * is to let two real transactions race. The fixture is committed in
 * {@code @BeforeEach}, then two threads call the transactional service at the
 * same time.
 * <p>
 * Checkout locks the User row, so the second thread waits and then sees an
 * empty cart: exactly one order is created. Cancellation locks the Order row,
 * so the second thread waits and then sees CANCELLED: both calls succeed.
 * <p>
 * The rows are deleted afterwards, and the fixture uses a random email, so this
 * class does not disturb the shared container.
 */
@SpringBootTest
class OrderConcurrencyTest extends AbstractIntegrationTest {

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
    private JdbcTemplate jdbc;

    private ExecutorService executor;
    private long userId;
    private long productId;
    private long categoryId;

    private final ShippingAddress shipping = new ShippingAddress(
            "Bob Smith", "1 Main St", "Apt 2", "Springfield", "12345", "US");

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);

        Category category = categoryRepository.saveAndFlush(
                new Category("Concurrency", "concurrency", "Concurrency tests"));
        categoryId = category.getId();

        Product product = productRepository.saveAndFlush(new Product(
                category, "SKU-CONC-1", "Concurrency Keyboard", "concurrency-keyboard",
                null, new BigDecimal("10.00"), 10, null));
        productId = product.getId();

        User user = userRepository.saveAndFlush(new User(
                "concurrency-" + UUID.randomUUID() + "@example.com",
                "password-hash", "Con", "Currency", Role.CUSTOMER));
        userId = user.getId();

        cartService.addItem(userId, productId, 2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbc.update("""
                DELETE FROM order_items
                WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)
                """, userId);
        jdbc.update("DELETE FROM orders WHERE user_id = ?", userId);
        jdbc.update("""
                DELETE FROM cart_items
                WHERE cart_id IN (SELECT id FROM carts WHERE user_id = ?)
                """, userId);
        jdbc.update("DELETE FROM carts WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
    }

    @Test
    void twoConcurrentCheckoutsCreateExactlyOneOrder() throws Exception {
        CountDownLatch start = new CountDownLatch(1);

        Future<OrderResponse> first = executor.submit(() -> {
            start.await();
            return orderService.checkout(userId, shipping);
        });
        Future<OrderResponse> second = executor.submit(() -> {
            start.await();
            return orderService.checkout(userId, shipping);
        });

        start.countDown();

        int successes = 0;
        int emptyCart = 0;
        for (Future<OrderResponse> future : List.of(first, second)) {
            try {
                OrderResponse response = future.get(10, TimeUnit.SECONDS);
                assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
                successes++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(EmptyCartException.class);
                emptyCart++;
            }
        }

        assertThat(successes).isEqualTo(1);
        assertThat(emptyCart).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM orders WHERE user_id = ?", userId))
                .isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM cart_items
                WHERE cart_id = (SELECT id FROM carts WHERE user_id = ?)
                """, userId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId))
                .isEqualTo(10);
    }

    @Test
    void twoConcurrentCancelsBothSucceed() throws Exception {
        OrderResponse order = orderService.checkout(userId, shipping);
        long orderId = order.id();

        CountDownLatch start = new CountDownLatch(1);

        Future<OrderResponse> first = executor.submit(() -> {
            start.await();
            return orderService.cancel(userId, orderId);
        });
        Future<OrderResponse> second = executor.submit(() -> {
            start.await();
            return orderService.cancel(userId, orderId);
        });

        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).status())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(second.get(10, TimeUnit.SECONDS).status())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("CANCELLED");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
