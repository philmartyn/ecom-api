package com.example.ecom.payment;

import com.example.ecom.common.exception.PaymentProviderException;
import com.example.ecom.payment.gateway.GatewayIntent;
import com.example.ecom.payment.gateway.GatewayStatus;
import com.example.ecom.payment.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Expires stale reservations every minute and doubles as the reconciliation
 * backstop for webhooks we never received.
 *
 * <p>For each expired attempt it asks the provider what actually happened:
 * a succeeded intent is finalised (the customer paid, keep the stock), anything
 * still cancellable is released, and a processing intent or an unreachable
 * provider is left for the next run. Because {@link PaymentAttemptCloser} is
 * idempotent, a sweep racing a webhook cannot release and settle the same
 * attempt: one of them wins, the other is a no-op.</p>
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.payments.sweeper.enabled", havingValue = "true")
public class PaymentReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(PaymentReservationSweeper.class);

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptCloser paymentAttemptCloser;
    private final PaymentGateway paymentGateway;
    private final Duration reservationTtl;
    private final TransactionTemplate transactions;

    public PaymentReservationSweeper(PaymentRepository paymentRepository,
                                     PaymentAttemptCloser paymentAttemptCloser,
                                     PaymentGateway paymentGateway,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${app.payments.reservation-ttl:30m}")
                                     Duration reservationTtl) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptCloser = paymentAttemptCloser;
        this.paymentGateway = paymentGateway;
        this.reservationTtl = reservationTtl;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}: the next run starts one minute
     * after the previous one finishes, so a slow provider cannot stack sweeps.
     */
    @Scheduled(fixedDelayString = "${app.payments.sweep-interval:60s}",
            initialDelayString = "${app.payments.sweep-interval:60s}")
    void scheduledSweep() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("Payment reservation sweep failed", e);
        }
    }

    /**
     * One pass. Public so tests can drive it directly instead of waiting a
     * minute. Returns how many attempts were actually closed.
     */
    public int sweep() {
        Instant cutoff = Instant.now().minus(reservationTtl);

        // The row locks live only for this short read. Holding them across the
        // provider calls below would pin a row for the length of a network round
        // trip, which is exactly what the reservation design avoids.
        List<Long> expired = transactions.execute(status ->
                paymentRepository.findExpiredPendingForUpdate(cutoff).stream()
                        .map(Payment::getId)
                        .toList());

        if (expired == null || expired.isEmpty()) {
            return 0;
        }

        int closed = 0;
        for (Long paymentId : expired) {
            if (reconcile(paymentId)) {
                closed++;
            }
        }
            return closed;
    }

    private boolean reconcile(Long paymentId) {
        // Unlocked read: the closer re-locks and re-checks before changing
        // anything, so a webhook that lands in between simply wins.
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.PENDING) {
            return false;
        }

        String providerRef = payment.getProviderRef();

        if (providerRef == null) {
            // A providerless attempt charged nothing and owes nothing, so
            // finishing it locally is safe. Any other pending row with no
            // reference means we crashed between reserving and calling out;
            // settling that would mark an unpaid order PAID, so it stays put.
            if (PaymentReservationService.NO_PROVIDER.equals(payment.getProvider())) {
                paymentAttemptCloser.settle(paymentId);
                return true;
            }
            log.error("Pending payment {} has no provider reference", paymentId);
            return false;
        }

        GatewayIntent intent;
        try {
            intent = paymentGateway.retrieve(providerRef);
        } catch (PaymentProviderException e) {
            log.warn("Cannot reconcile payment {}, will retry: {}",
                    paymentId, e.getMessage());
            return false;
        }

        return switch (intent.status()) {
            case SUCCEEDED -> {
                paymentAttemptCloser.settle(paymentId);
                yield true;
            }
            case FAILED, CANCELED -> {
                paymentAttemptCloser.close(paymentId, intent.failureCode());
                yield true;
            }
            case PROCESSING -> false;
            case REQUIRES_PAYMENT_METHOD, REQUIRES_CONFIRMATION, REQUIRES_ACTION ->
                    release(paymentId, providerRef);
            };
        }

    private boolean release(long paymentId, String providerRef) {
        try {
            GatewayIntent canceled = paymentGateway.cancel(providerRef);

            if (canceled.status() == GatewayStatus.SUCCEEDED) {
                paymentAttemptCloser.settle(paymentId);
            } else {
                paymentAttemptCloser.close(paymentId, null);
            }
            return true;
        } catch (PaymentProviderException e) {
            log.warn("Cannot release payment {}; will retry: {}",
                    paymentId, e.getMessage());
            return false;
        }
    }
}
