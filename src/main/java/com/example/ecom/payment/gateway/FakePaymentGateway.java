package com.example.ecom.payment.gateway;

import com.example.ecom.common.exception.PaymentProviderException;
import com.example.ecom.payment.Payment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name = "app.payments.provider",
        havingValue = "fake", matchIfMissing = true)
public class FakePaymentGateway implements PaymentGateway {

    private final Map<String, GatewayStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, String> failureCodes = new ConcurrentHashMap<>();

    private final AtomicReference<String> nextCreateFailure = new AtomicReference<>();
    private final AtomicBoolean nextRetrieveFailure = new AtomicBoolean();
    private final AtomicBoolean nextCancelFailure = new AtomicBoolean();

    @Override
    public GatewayIntent create(Payment payment) {
        String failure = nextCreateFailure.getAndSet(null);
        if (failure != null) {
            throw new PaymentProviderException(failure);
        }

        String providerRef = "pi_fake_" + payment.getId();
        statuses.putIfAbsent(providerRef, GatewayStatus.REQUIRES_PAYMENT_METHOD);

        return new GatewayIntent(
                providerRef,
                statuses.get(providerRef),
                "cs_fake_" + payment.getId() + "_secret",
                failureCodes.get(providerRef));
    }

    @Override
    public GatewayIntent retrieve(String providerRef) {
        if (nextRetrieveFailure.getAndSet(false)) {
            throw new PaymentProviderException("retrieve failed for " + providerRef);
        }
        return new GatewayIntent(providerRef, statusOf(providerRef), null,
                failureCodes.get(providerRef));
    }

    @Override
    public GatewayIntent cancel(String providerRef) {
        if (nextCancelFailure.getAndSet(false)) {
            throw new PaymentProviderException("cancel failed for " + providerRef);
        }

        GatewayStatus current = statusOf(providerRef);
        if (current != GatewayStatus.SUCCEEDED) {
            statuses.put(providerRef, GatewayStatus.CANCELED);
        }
        return new GatewayIntent(providerRef, statusOf(providerRef), null, null);
    }

    @Override
    public Optional<String> publishableKey() {
        return Optional.empty();
    }

    private GatewayStatus statusOf(String providerRef) {
        GatewayStatus status = statuses.get(providerRef);
        if (status == null) {
            throw new PaymentProviderException("Unknown payment intent  " + providerRef);
        }
        return status;
    }

    // --- scripting (tests only) ---------------------------------------------

    public void failNextCreate(String failureCode) {
        nextCreateFailure.set(failureCode);
    }

    public void failNextRetrieve() {
        nextRetrieveFailure.set(true);
    }

    public void failNextCancel() {
        nextCancelFailure.set(true);
    }

    public void setStatus(String providerRef, GatewayStatus status) {
        statuses.put(providerRef, status);
    }

    public void setFailureCode(String providerRef, String failureCode) {
        failureCodes.put(providerRef, failureCode);
    }
}
