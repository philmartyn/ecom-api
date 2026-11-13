package com.example.ecom.payment.gateway;

import com.example.ecom.common.exception.PaymentProviderException;
import com.example.ecom.payment.Payment;
import com.stripe.StripeClient;
import com.stripe.exception.ApiException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.service.PaymentIntentService;
import com.stripe.service.V1Services;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The SDK-facing adapter. The Stripe client is stubbed, so this pins the
 * translation layer — amounts, idempotency, status mapping and error wrapping —
 * without a network call or a Stripe key.
 */
class StripePaymentGatewayTest {

    private static final long PAYMENT_ID = 7L;
    private static final String PUBLISHABLE = "pk_test_public";

    private StripeClient client;
    private PaymentIntentService intents;
    private StripePaymentGateway gateway;

    @BeforeEach
    void setUp() {
        client = mock(StripeClient.class);
        V1Services v1 = mock(V1Services.class);
        intents = mock(PaymentIntentService.class);
        given(client.v1()).willReturn(v1);
        given(v1.paymentIntents()).willReturn(intents);
        gateway = new StripePaymentGateway(client, PUBLISHABLE);
    }

    // --- create -------------------------------------------------------------

    @Test
    void createSendsMinorUnitsCurrencyAndAnIdempotencyKey() throws StripeException {
        given(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .willReturn(intent("pi_123", "requires_payment_method", "cs_123", null));

        GatewayIntent created = gateway.create(payment(new BigDecimal("89.99")));

        assertThat(created.providerRef()).isEqualTo("pi_123");
        assertThat(created.status()).isEqualTo(GatewayStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(created.clientSecret()).isEqualTo("cs_123");
        assertThat(created.failureCode()).isNull();

        ArgumentCaptor<PaymentIntentCreateParams> params =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(intents).create(params.capture(), options.capture());

        assertThat(params.getValue().getAmount()).isEqualTo(8999L);
        assertThat(params.getValue().getCurrency()).isEqualTo("usd");
        assertThat(params.getValue().getDescription()).isEqualTo("Order payment 7");
        assertThat(params.getValue().getMetadata()).containsEntry("payment_id", "7");
        assertThat(params.getValue().getAutomaticPaymentMethods().getEnabled()).isTrue();
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("payment-7");
    }

    @Test
    void createWrapsAStripeFailure() throws StripeException {
        willThrow(new ApiException("boom", "req_1", "api_error", 500, null))
                .given(intents).create(any(PaymentIntentCreateParams.class), any(RequestOptions.class));

        assertThatThrownBy(() -> gateway.create(payment(new BigDecimal("10.00"))))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("Stripe create failed");
    }

    // --- retrieve -----------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "succeeded, SUCCEEDED",
            "canceled, CANCELED",
            "processing, PROCESSING",
            "requires_confirmation, REQUIRES_CONFIRMATION",
            "requires_action, REQUIRES_ACTION",
            "requires_payment_method, REQUIRES_PAYMENT_METHOD",
            "some_future_status, PROCESSING"
    })
    void retrieveTranslatesTheProviderStatus(String stripeStatus, GatewayStatus expected) throws StripeException {
        given(intents.retrieve("pi_123"))
                .willReturn(intent("pi_123", stripeStatus, "cs_123", null));

        assertThat(gateway.retrieve("pi_123").status()).isEqualTo(expected);
    }

    @Test
    void retrieveMapsRequiresPaymentMethodWithAnErrorToFailed() throws StripeException {
        given(intents.retrieve("pi_123"))
                .willReturn(intent("pi_123", "requires_payment_method", "cs_123", "card_declined"));

        GatewayIntent retrieved = gateway.retrieve("pi_123");

        assertThat(retrieved.status()).isEqualTo(GatewayStatus.FAILED);
        assertThat(retrieved.failureCode()).isEqualTo("card_declined");
    }

    @Test
    void retrieveWrapsAStripeFailure() throws StripeException {
        willThrow(new ApiException("boom", "req_1", "api_error", 500, null))
                .given(intents).retrieve("pi_123");

        assertThatThrownBy(() -> gateway.retrieve("pi_123"))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("Stripe retrieve failed");
    }

    // --- cancel -------------------------------------------------------------

    @Test
    void cancelReturnsTheProviderState() throws StripeException {
        given(intents.cancel("pi_123"))
                .willReturn(intent("pi_123", "canceled", null, null));

        assertThat(gateway.cancel("pi_123").status()).isEqualTo(GatewayStatus.CANCELED);
    }

    @Test
    void cancelFallsBackToRetrieveWhenStripeRefusesToCancel() throws StripeException {
        willThrow(new InvalidRequestException(
                "already succeeded", null, "req_1", "invalid_request", 400, null))
                .given(intents).cancel("pi_123");
        given(intents.retrieve("pi_123"))
                .willReturn(intent("pi_123", "succeeded", "cs_123", null));

        GatewayIntent canceled = gateway.cancel("pi_123");

        assertThat(canceled.status()).isEqualTo(GatewayStatus.SUCCEEDED);
        verify(intents).retrieve("pi_123");
    }

    @Test
    void cancelWrapsAnyOtherStripeFailure() throws StripeException {
        willThrow(new ApiException("boom", "req_1", "api_error", 500, null))
                .given(intents).cancel("pi_123");

        assertThatThrownBy(() -> gateway.cancel("pi_123"))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("Stripe cancel failed");
    }

    // --- keys and configuration --------------------------------------------

    @Test
    void publishableKeyIsExposedToTheClient() {
        assertThat(gateway.publishableKey()).contains(PUBLISHABLE);
    }

    @Test
    void aBlankSecretKeyFailsConstruction() {
        assertThatThrownBy(() -> new StripePaymentGateway(" ", PUBLISHABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.payments.secret-key");
    }

    @Test
    void aBlankPublishableKeyFailsConstruction() {
        assertThatThrownBy(() -> new StripePaymentGateway("sk_test_123", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.payments.publishable-key");
    }

    // --- fixtures -----------------------------------------------------------

    private static Payment payment(BigDecimal amount) {
        Payment payment = mock(Payment.class);
        given(payment.getId()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(amount);
        given(payment.getCurrency()).willReturn("USD");
        return payment;
    }

    private static PaymentIntent intent(String id, String status, String clientSecret,
                                        String failureCode) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        intent.setStatus(status);
        intent.setClientSecret(clientSecret);
        if (failureCode != null) {
            StripeError error = new StripeError();
            error.setCode(failureCode);
            intent.setLastPaymentError(error);
        }
        return intent;
    }
}
