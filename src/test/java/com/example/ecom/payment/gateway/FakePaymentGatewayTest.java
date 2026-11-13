package com.example.ecom.payment.gateway;

import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.Product;
import com.example.ecom.common.exception.PaymentProviderException;
import com.example.ecom.order.Order;
import com.example.ecom.order.ShippingAddress;
import com.example.ecom.payment.Payment;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fake provider used by the service, integration and concurrency tests.
 * <p>
 * It has to be deterministic and scriptable: a create returns a predictable
 * reference and client secret, and a test can decide what the next retrieve or
 * cancel sees. That is what lets the sweeper and cancel races be tested without
 * Stripe or a network.
 */
class FakePaymentGatewayTest {

    private final Category category = new Category("Books", "books", "Paperbacks");
    private final User user = new User(
            "bob@example.com", "password-hash", "Bob", "Smith", Role.CUSTOMER);
    private final ShippingAddress shipping = new ShippingAddress(
            "Bob Smith", "1 Main St", "Apt 2", "Springfield", "12345", "US");

    private final FakePaymentGateway gateway = new FakePaymentGateway();

    // --- create -------------------------------------------------------------

    @Test
    void createReturnsADeterministicPendingIntent() {
        GatewayIntent intent = gateway.create(payment(11L));

        assertThat(intent.providerRef()).isEqualTo("pi_fake_11");
        assertThat(intent.status()).isEqualTo(GatewayStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(intent.clientSecret()).isEqualTo("cs_fake_11_secret");
        assertThat(intent.failureCode()).isNull();
    }

    @Test
    void createIsDeterministicForTheSamePayment() {
        GatewayIntent first = gateway.create(payment(11L));
        GatewayIntent second = gateway.create(payment(11L));

        assertThat(second.providerRef()).isEqualTo(first.providerRef());
        assertThat(second.clientSecret()).isEqualTo(first.clientSecret());
    }

    @Test
    void aScriptedCreateFailureIsThrownOnceAndRegistersNothing() {
        gateway.failNextCreate("processing_error");

        assertThatThrownBy(() -> gateway.create(payment(11L)))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("processing_error");

        // The failure is one-shot and the intent was never registered.
        assertThatThrownBy(() -> gateway.retrieve("pi_fake_11"))
                .isInstanceOf(PaymentProviderException.class);
        assertThat(gateway.create(payment(11L)).providerRef()).isEqualTo("pi_fake_11");
    }

    // --- retrieve -----------------------------------------------------------

    @Test
    void retrieveReturnsTheScriptedStatus() {
        gateway.create(payment(11L));
        gateway.setStatus("pi_fake_11", GatewayStatus.SUCCEEDED);

        GatewayIntent intent = gateway.retrieve("pi_fake_11");

        assertThat(intent.status()).isEqualTo(GatewayStatus.SUCCEEDED);
        assertThat(intent.failureCode()).isNull();
    }

    @Test
    void retrieveCarriesAScriptedFailureCode() {
        gateway.create(payment(11L));
        gateway.setStatus("pi_fake_11", GatewayStatus.FAILED);
        gateway.setFailureCode("pi_fake_11", "card_declined");

        GatewayIntent intent = gateway.retrieve("pi_fake_11");

        assertThat(intent.status()).isEqualTo(GatewayStatus.FAILED);
        assertThat(intent.failureCode()).isEqualTo("card_declined");
    }

    @Test
    void retrieveOfAnUnknownReferenceIsAProviderFailure() {
        assertThatThrownBy(() -> gateway.retrieve("pi_unknown"))
                .isInstanceOf(PaymentProviderException.class);
    }

    @Test
    void aScriptedRetrieveFailureIsThrownOnce() {
        gateway.create(payment(11L));
        gateway.failNextRetrieve();

        assertThatThrownBy(() -> gateway.retrieve("pi_fake_11"))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(gateway.retrieve("pi_fake_11").status())
                .isEqualTo(GatewayStatus.REQUIRES_PAYMENT_METHOD);
    }

    // --- cancel -------------------------------------------------------------

    @Test
    void cancelMarksTheIntentCanceled() {
        gateway.create(payment(11L));

        GatewayIntent canceled = gateway.cancel("pi_fake_11");

        assertThat(canceled.status()).isEqualTo(GatewayStatus.CANCELED);
        assertThat(gateway.retrieve("pi_fake_11").status())
                .isEqualTo(GatewayStatus.CANCELED);
    }

    @Test
    void cancellingAnAlreadyCanceledIntentIsIdempotent() {
        gateway.create(payment(11L));
        gateway.cancel("pi_fake_11");

        assertThat(gateway.cancel("pi_fake_11").status())
                .isEqualTo(GatewayStatus.CANCELED);
    }

    @Test
    void cancelOfAnUnknownReferenceIsAProviderFailure() {
        assertThatThrownBy(() -> gateway.cancel("pi_unknown"))
                .isInstanceOf(PaymentProviderException.class);
    }

    @Test
    void aScriptedCancelFailureIsThrownOnceAndLeavesTheIntentPending() {
        gateway.create(payment(11L));
        gateway.failNextCancel();

        assertThatThrownBy(() -> gateway.cancel("pi_fake_11"))
                .isInstanceOf(PaymentProviderException.class);
        assertThat(gateway.retrieve("pi_fake_11").status())
                .isEqualTo(GatewayStatus.REQUIRES_PAYMENT_METHOD);
    }

    // --- fixture ------------------------------------------------------------

    private Payment payment(long id) {
        Order order = new Order(user, shipping);
        order.addItem(new Product(
                category, "SKU-1", "Mechanical Keyboard", "mechanical-keyboard",
                "Clicky keys", new BigDecimal("89.99"), 10, null), 2);

        Payment payment = new Payment(order, "fake");
        ReflectionTestUtils.setField(payment, "id", id);
        return payment;
    }
}
