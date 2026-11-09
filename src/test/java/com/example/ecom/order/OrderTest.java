package com.example.ecom.order;

import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.Product;
import com.example.ecom.common.exception.OrderNotCancellableException;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Order aggregate and its response mapping, without Spring or a database.
 * <p>
 * The entity owns the structural invariants: a new Order is PENDING in USD with
 * zero totals, each line snapshots the Product's name and unit price, and the
 * totals are derived from the lines. It deliberately does <em>not</em> know
 * about stock or Active — those are catalog policy and live in the service,
 * which turns them into 409.
 * <p>
 * Cancellation is a state transition, not a delete: PENDING moves to CANCELLED,
 * CANCELLED is idempotent, and anything PAID or later is rejected. The PAID,
 * SHIPPED and DELIVERED states cannot be reached through the public API in this
 * slice, so {@link ReflectionTestUtils} sets the private field directly. That
 * keeps a test-only seam out of the production class.
 * <p>
 * Money is always scale 2, including the empty order, so a client never has to
 * guess whether 0 renders as {@code 0} or {@code 0.00}.
 */
class OrderTest {

    private static final Instant CREATED = Instant.parse("2026-11-08T12:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-11-08T12:05:00Z");

    private final Category category = new Category("Books", "books", "Paperbacks");
    private final User user = new User(
            "bob@example.com", "password-hash", "Bob", "Smith", Role.CUSTOMER);
    private final ShippingAddress shipping = new ShippingAddress(
            "Bob Smith", "1 Main St", "Apt 2", "Springfield", "12345", "us");

    // --- structure ---------------------------------------------------------

    @Test
    void newOrderIsPendingInUsdWithZeroTotals() {
        Order order = new Order(user, shipping);

        assertThat(order.getUser()).isSameAs(user);
        assertThat(order.getShippingAddress()).isSameAs(shipping);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getCurrency()).isEqualTo("USD");
        assertThat(order.getSubtotal()).isEqualByComparingTo("0.00");
        assertThat(order.getTotal()).isEqualByComparingTo("0.00");
        assertThat(order.getSubtotal().scale()).isEqualTo(2);
        assertThat(order.getTotal().scale()).isEqualTo(2);
        assertThat(order.getItems()).isEmpty();
        assertThat(order.getTotalQuantity()).isZero();
    }

    @Test
    void addItemSnapshotsTheProductNameAndUnitPrice() {
        Order order = new Order(user, shipping);
        Product keyboard = product("SKU-1", "Mechanical Keyboard",
                "mechanical-keyboard", "89.99", 10);

        order.addItem(keyboard, 2);

        assertThat(order.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProduct()).isSameAs(keyboard);
            assertThat(item.getProductName()).isEqualTo("Mechanical Keyboard");
            assertThat(item.getUnitPrice()).isEqualByComparingTo("89.99");
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.lineTotal()).isEqualByComparingTo("179.98");
            assertThat(item.lineTotal().scale()).isEqualTo(2);
        });
    }

    @Test
    void totalsAreDerivedFromTheLines() {
        Order order = new Order(user, shipping);
        order.addItem(product("SKU-1", "Mechanical Keyboard",
                "mechanical-keyboard", "89.99", 10), 2);
        order.addItem(product("SKU-2", "Wireless Mouse",
                "wireless-mouse", "39.99", 10), 3);

        // 2 * 89.99 + 3 * 39.99 = 179.98 + 119.97
        assertThat(order.getSubtotal()).isEqualByComparingTo("299.95");
        assertThat(order.getTotal()).isEqualByComparingTo("299.95");
        assertThat(order.getTotalQuantity()).isEqualTo(5);
        assertThat(order.getItems()).hasSize(2);
    }

    @Test
    void addItemRejectsANonPositiveQuantity() {
        Order order = new Order(user, shipping);
        Product keyboard = product("SKU-1", "Mechanical Keyboard",
                "mechanical-keyboard", "89.99", 10);

        assertThatThrownBy(() -> order.addItem(keyboard, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.addItem(keyboard, -1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(order.getItems()).isEmpty();
        assertThat(order.getSubtotal()).isEqualByComparingTo("0.00");
    }

    // --- cancellation ------------------------------------------------------

    @Test
    void cancelMovesAPendingOrderToCancelled() {
        Order order = new Order(user, shipping);

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancellingAnAlreadyCancelledOrderIsIdempotent() {
        Order order = new Order(user, shipping);
        order.cancel();

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelRejectsAnOrderThatHasMovedBeyondPending() {
        for (OrderStatus status : List.of(
                OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED)) {
            Order order = new Order(user, shipping);
            ReflectionTestUtils.setField(order, "status", status);

            assertThatThrownBy(order::cancel)
                    .isInstanceOf(OrderNotCancellableException.class)
                    .hasMessageContaining(status.name());
            assertThat(order.getStatus()).isEqualTo(status);
        }
    }

    // --- shipping address --------------------------------------------------

    @Test
    void shippingAddressTrimsAndNormalizesItsFields() {
        ShippingAddress address = new ShippingAddress(
                "  Bob Smith  ", " 1 Main St ", "   ",
                " Springfield ", " 12345 ", " us ");

        assertThat(address.getName()).isEqualTo("Bob Smith");
        assertThat(address.getLine1()).isEqualTo("1 Main St");
        assertThat(address.getLine2()).isNull();
        assertThat(address.getCity()).isEqualTo("Springfield");
        assertThat(address.getPostalCode()).isEqualTo("12345");
        assertThat(address.getCountry()).isEqualTo("US");
    }

    @Test
    void shippingAddressRejectsACountryThatIsNotTwoLetters() {
        assertThatThrownBy(() -> new ShippingAddress(
                "Bob", "1 Main St", null, "Springfield", "12345", "USA"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ShippingAddress(
                "Bob", "1 Main St", null, "Springfield", "12345", "U1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shippingAddressRejectsBlankRequiredFields() {
        assertThatThrownBy(() -> new ShippingAddress(
                " ", "1 Main St", null, "Springfield", "12345", "US"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ShippingAddress(
                "Bob", "1 Main St", null, " ", "12345", "US"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- response mapping --------------------------------------------------

    @Test
    void detailResponseCarriesTheSnapshotTotalsAndShipping() {
        Product keyboard = product("SKU-1", "Mechanical Keyboard",
                "mechanical-keyboard", "89.99", 10);
        ReflectionTestUtils.setField(keyboard, "id", 1L);

        Order order = new Order(user, shipping);
        order.addItem(keyboard, 2);
        identify(order, 7L);

        OrderResponse response = OrderResponse.from(order);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.subtotal()).isEqualByComparingTo("179.98");
        assertThat(response.total()).isEqualByComparingTo("179.98");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.totalQuantity()).isEqualTo(2);
        assertThat(response.shipping().country()).isEqualTo("US");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(1L);
            assertThat(item.productName()).isEqualTo("Mechanical Keyboard");
            assertThat(item.unitPrice()).isEqualByComparingTo("89.99");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.lineTotal()).isEqualByComparingTo("179.98");
        });
        assertThat(response.createdAt()).isEqualTo(CREATED);
        assertThat(response.updatedAt()).isEqualTo(UPDATED);
    }

    @Test
    void summaryResponseHasNoLinesOrShipping() {
        Order order = new Order(user, shipping);
        order.addItem(product("SKU-1", "Mechanical Keyboard",
                "mechanical-keyboard", "89.99", 10), 2);
        identify(order, 7L);

        OrderSummaryResponse summary = OrderSummaryResponse.from(order);

        assertThat(summary.id()).isEqualTo(7L);
        assertThat(summary.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(summary.subtotal()).isEqualByComparingTo("179.98");
        assertThat(summary.total()).isEqualByComparingTo("179.98");
        assertThat(summary.currency()).isEqualTo("USD");
        assertThat(summary.totalQuantity()).isEqualTo(2);
        assertThat(summary.createdAt()).isEqualTo(CREATED);
    }

    // --- fixtures ----------------------------------------------------------

    private Product product(String sku, String name, String slug,
                            String price, int stock) {
        return new Product(category, sku, name, slug, "Description",
                new BigDecimal(price), stock, "https://example.com/" + slug + ".png");
    }

    private static void identify(Order order, long id) {
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "createdAt", CREATED);
        ReflectionTestUtils.setField(order, "updatedAt", UPDATED);
    }
}
