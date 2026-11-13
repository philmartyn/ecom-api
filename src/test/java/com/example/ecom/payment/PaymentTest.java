package com.example.ecom.payment;

import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.Product;
import com.example.ecom.order.Order;
import com.example.ecom.order.ShippingAddress;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Payment aggregate, without Spring or a database.
 * <p>
 * A Payment Attempt is created for a PENDING Order and snapshots the amount and
 * currency it is charging. It starts PENDING, has no provider reference until
 * the gateway call succeeds, and has no failure code. The provider reference is
 * deliberately not exposed by the API.
 * <p>
 * Success and failure are terminal: a PENDING attempt may move to SUCCEEDED or
 * FAILED, the same terminal state is idempotent, and any other transition is
 * rejected. That is what lets a duplicate webhook be a no-op without a second
 * stock change.
 */
class PaymentTest {

    private static final Instant CREATED = Instant.parse("2026-11-09T12:00:00Z");

    private final Category category = new Category("Books", "books", "Paperbacks");
    private final User user = new User(
            "bob@example.com", "password-hash", "Bob", "Smith", Role.CUSTOMER);
    private final ShippingAddress shipping = new ShippingAddress(
            "Bob Smith", "1 Main St", "Apt 2", "Springfield", "12345", "US");

    // --- creation ----------------------------------------------------------

    @Test
    void newAttemptIsPendingAndCopiesTheOrderAmount() {
        Order order = new Order(user, shipping);
        order.addItem(product("SKU-1", "Mechanical Keyboard",
                "mechanical-keyboard", "89.99", 10), 2);

        Payment payment = new Payment(order, "stripe");

        assertThat(payment.getOrder()).isSameAs(order);
        assertThat(payment.getProvider()).isEqualTo("stripe");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getProviderRef()).isNull();
        assertThat(payment.getFailureCode()).isNull();
        assertThat(payment.getAmount()).isEqualByComparingTo("179.98");
        assertThat(payment.getAmount().scale()).isEqualTo(2);
        assertThat(payment.getCurrency()).isEqualTo("USD");
    }

    @Test
    void aZeroAmountAttemptIsStillPending() {
        Order order = new Order(user, shipping);
        order.addItem(product("SKU-1", "Free Sticker",
                "free-sticker", "0.00", 10), 1);

        Payment payment = new Payment(order, "none");

        assertThat(payment.getAmount()).isEqualByComparingTo("0.00");
        assertThat(payment.getAmount().scale()).isEqualTo(2);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    // --- provider reference ------------------------------------------------

    @Test
    void attachProviderRefStoresTheReferenceWhilePending() {
        Payment payment = attempt();

        payment.attachProviderRef("pi_123");

        assertThat(payment.getProviderRef()).isEqualTo("pi_123");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void attachingTheSameProviderRefTwiceIsIdempotent() {
        Payment payment = attempt();
        payment.attachProviderRef("pi_123");

        payment.attachProviderRef("pi_123");

        assertThat(payment.getProviderRef()).isEqualTo("pi_123");
    }

    @Test
    void attachingADifferentProviderRefIsRejected() {
        Payment payment = attempt();
        payment.attachProviderRef("pi_123");

        assertThatThrownBy(() -> payment.attachProviderRef("pi_other"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payment.getProviderRef()).isEqualTo("pi_123");
    }

    @Test
    void attachingAProviderRefAfterATerminalStateIsRejected() {
        Payment payment = attempt();
        payment.markSucceeded();

        assertThatThrownBy(() -> payment.attachProviderRef("pi_123"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payment.getProviderRef()).isNull();
    }

    // --- success ------------------------------------------------------------

    @Test
    void markSucceededMovesPendingToSucceeded() {
        Payment payment = attempt();

        payment.markSucceeded();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getFailureCode()).isNull();
    }

    @Test
    void markSucceededIsIdempotent() {
        Payment payment = attempt();
        payment.markSucceeded();

        payment.markSucceeded();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void markSucceededRejectsAFailedPayment() {
        Payment payment = attempt();
        payment.markFailed("card_declined");

        assertThatThrownBy(payment::markSucceeded)
                .isInstanceOf(IllegalStateException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void markSucceededRejectsARefundedPayment() {
        Payment payment = attempt();
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.REFUNDED);

        assertThatThrownBy(payment::markSucceeded)
                .isInstanceOf(IllegalStateException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    // --- failure ------------------------------------------------------------

    @Test
    void markFailedRecordsTheFailureCode() {
        Payment payment = attempt();

        payment.markFailed("card_declined");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("card_declined");
    }

    @Test
    void markFailedAcceptsANullFailureCodeForACancellation() {
        Payment payment = attempt();

        payment.markFailed(null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isNull();
    }

    @Test
    void markFailedIsIdempotentAndKeepsTheFirstCode() {
        Payment payment = attempt();
        payment.markFailed("card_declined");

        payment.markFailed("processing_error");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("card_declined");
    }

    @Test
    void markFailedRejectsASucceededPayment() {
        Payment payment = attempt();
        payment.markSucceeded();

        assertThatThrownBy(() -> payment.markFailed("card_declined"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getFailureCode()).isNull();
    }

    // --- response mapping ---------------------------------------------------

    @Test
    void responseCarriesTheAttemptStateWithoutSecrets() {
        Payment payment = attempt();
        payment.attachProviderRef("pi_123");
        payment.markFailed("card_declined");
        ReflectionTestUtils.setField(payment, "id", 11L);
        ReflectionTestUtils.setField(payment, "createdAt", CREATED);

        PaymentResponse response = PaymentResponse.from(payment);

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.amount()).isEqualByComparingTo("179.98");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.failureCode()).isEqualTo("card_declined");
        assertThat(response.createdAt()).isEqualTo(CREATED);
    }

    // --- fixtures -----------------------------------------------------------

    private Payment attempt() {
        Order order = new Order(user, shipping);
        order.addItem(product("SKU-1", "Mechanical Keyboard",
                "mechanical-keyboard", "89.99", 10), 2);
        return new Payment(order, "stripe");
    }

    private Product product(String sku, String name, String slug,
                            String price, int stock) {
        return new Product(category, sku, name, slug, "Description",
                new BigDecimal(price), stock, "https://example.com/" + slug + ".png");
    }
}
