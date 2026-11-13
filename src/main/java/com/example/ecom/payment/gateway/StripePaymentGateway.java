package com.example.ecom.payment.gateway;

import com.example.ecom.common.exception.PaymentProviderException;
import com.example.ecom.payment.Payment;
import com.example.ecom.payment.StripeAmounts;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.Optional;

/**
 * The only class in the application that imports {@code com.stripe.*}.
 *
 * <p>Fail-fast keys: this bean only exists when {@code app.payments.provider=stripe},
 * and it refuses to be constructed without keys. A missing secret then breaks
 * startup, not the first customer's payment.</p>
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "stripe")
public class StripePaymentGateway implements PaymentGateway {

    private final StripeClient client;
    private final String publishableKey;

    @Autowired
    public StripePaymentGateway(
            @Value("${app.payments.secret-key:}") String secretKey,
            @Value("${app.payments.publishable-key:}") String publishableKey
    ) {
        this(newClient(secretKey), publishableKey);
    }

    private static StripeClient newClient(String secretKey) {
        Assert.hasText(secretKey,
                "app.payments.secret-key is required when app.payments.provider=stripe");
        return new StripeClient(secretKey);
    }

    /**
     * Seam for tests: the SDK client is final and its constructors talk to the
     * network configuration, so a stubbed client is injected here rather than
     * built from a key.
     */
    StripePaymentGateway(StripeClient client, String publishableKey) {
        Assert.hasText(publishableKey,
                "app.payments.publishable-key is required when app.payments.provider=stripe");

        this.client = client;
        this.publishableKey = publishableKey;
    }

    @Override
    public GatewayIntent create(Payment payment) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(StripeAmounts.toMinorUnits(payment.getAmount()))
                .setCurrency(StripeAmounts.toStripeCurrency(payment.getCurrency()))
                .setDescription("Order payment " + payment.getId())
                .putMetadata("payment_id", String.valueOf(payment.getId()))
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build())
                .build();
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("payment-" + payment.getId())
                .build();

        try {
            return toIntent(client.v1().paymentIntents().create(params, options));
        } catch (StripeException e) {
            throw new PaymentProviderException(
                    "Stripe create failed: " + e.getMessage(), e);
        }
    }


    @Override
    public GatewayIntent retrieve(String providerRef) {
        try {
            return toIntent(client.v1().paymentIntents().retrieve(providerRef));
        } catch (StripeException e) {
            throw new PaymentProviderException(
                    "Stripe retrieve failed: " + e.getMessage(), e);
        }
    }

    @Override
    public GatewayIntent cancel(String providerRef) {
        try {
            return toIntent(client.v1().paymentIntents().cancel(providerRef));
        } catch (InvalidRequestException e) {
            return retrieve(providerRef);
        }

            catch (StripeException e) {
            throw new PaymentProviderException(
                    "Stripe cancel failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> publishableKey() {
        return Optional.of(publishableKey);
    }

    private GatewayIntent toIntent(PaymentIntent paymentIntent) {
        return new GatewayIntent(
                paymentIntent.getId(),
                statusOf(paymentIntent),
                paymentIntent.getClientSecret(),
                failureCodeOf(paymentIntent));
    }

    private GatewayStatus statusOf(PaymentIntent paymentIntent) {
        String status = paymentIntent.getStatus();
        if (status == null) {
            return GatewayStatus.SUCCEEDED;
        }
        return switch (status) {
            case "succeeded" -> GatewayStatus.SUCCEEDED;
            case "canceled" -> GatewayStatus.CANCELED;
            case "processing" -> GatewayStatus.PROCESSING;
            case "requires_confirmation" -> GatewayStatus.REQUIRES_CONFIRMATION;
            case "requires_action" -> GatewayStatus.REQUIRES_ACTION;
            case "requires_payment_method" -> paymentIntent.getLastPaymentError() != null
                    ? GatewayStatus.FAILED
                    : GatewayStatus.REQUIRES_PAYMENT_METHOD;
            default -> GatewayStatus.PROCESSING;
        };
    }

    private String failureCodeOf(PaymentIntent paymentIntent) {
        return paymentIntent.getLastPaymentError() == null
                ? null
                : paymentIntent.getLastPaymentError().getCode();
    }
}
