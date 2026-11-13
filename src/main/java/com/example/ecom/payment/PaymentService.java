package com.example.ecom.payment;

import com.example.ecom.common.exception.OrderNotFoundException;
import com.example.ecom.common.exception.PaymentNotCancellableException;
import com.example.ecom.common.exception.PaymentNotFoundException;
import com.example.ecom.common.exception.PaymentProviderException;
import com.example.ecom.order.OrderRepository;
import com.example.ecom.payment.gateway.GatewayIntent;
import com.example.ecom.payment.gateway.GatewayStatus;
import com.example.ecom.payment.gateway.PaymentGateway;
import com.example.ecom.payment.webhook.VerifiedPaymentEvent;
import com.example.ecom.payment.webhook.WebhookEventType;
import com.example.ecom.user.UserAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PaymentService {

    static final String PROVIDER_ERROR_CODE = "provider_error";

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final UserAccess userAccess;
    private final PaymentReservationService reservationService;
    private final PaymentAttemptCloser paymentAttemptCloser;
    private final PaymentGateway paymentGateway;


    public PaymentService(PaymentRepository paymentRepository,
                          OrderRepository orderRepository,
                          UserAccess userAccess,
                          PaymentReservationService reservationService,
                          PaymentAttemptCloser paymentAttemptCloser,
                          PaymentGateway paymentGateway) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.userAccess = userAccess;
        this.reservationService = reservationService;
        this.paymentAttemptCloser = paymentAttemptCloser;
        this.paymentGateway = paymentGateway;
    }

    public PaymentCreatedResponse create(long userId, long orderId) {
        userAccess.requireExists(userId);

        Payment payment = reservationService.reserve(userId, orderId);

        if (payment.getAmount().signum() == 0) {
            Payment settled = paymentAttemptCloser.settle(payment.getId());
            return PaymentCreatedResponse.from(settled, null, null);
        }

        GatewayIntent gatewayIntent;
        try {
            gatewayIntent = paymentGateway.create(payment);
        } catch (PaymentProviderException ex) {
            paymentAttemptCloser.close(payment.getId(), PROVIDER_ERROR_CODE);
            throw ex;
        }

        paymentAttemptCloser.attachProviderRef(payment.getId(), gatewayIntent.providerRef());

        return PaymentCreatedResponse.from(payment,
                gatewayIntent.clientSecret(),
                paymentGateway.publishableKey().orElse(null));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(long userId, long orderId) {
        requireOwnedOrder(userId, orderId);

        return paymentRepository.findByOrderIdOrderByCreatedAtDescIdDesc(orderId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    public PaymentResponse cancel(long userId, long orderId, long paymentId) {
        requireOwnedOrder(userId, orderId);

        Payment payment = paymentRepository.findByIdAndOrderId(paymentId, orderId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        return switch (payment.getStatus()) {
            case FAILED -> PaymentResponse.from(payment);
            case SUCCEEDED, REFUNDED ->
                    throw new PaymentNotCancellableException(payment.getStatus());
            case PENDING -> cancelPending(payment);
        };
    }

    private PaymentResponse cancelPending(Payment payment) {
        GatewayIntent gatewayIntent = paymentGateway.retrieve(payment.getProviderRef());

        if (gatewayIntent.status() == GatewayStatus.SUCCEEDED) {
            paymentAttemptCloser.settle(payment.getId());
            throw new PaymentNotCancellableException(PaymentStatus.SUCCEEDED);
        }

        if (gatewayIntent.status() == GatewayStatus.FAILED) {
            return PaymentResponse.from(paymentAttemptCloser.close(payment.getId(), gatewayIntent.failureCode()));
        }

        GatewayIntent canceled = paymentGateway.cancel(payment.getProviderRef());

        if (canceled.status() == GatewayStatus.SUCCEEDED) {
            paymentAttemptCloser.settle(payment.getId());
            throw new PaymentNotCancellableException(PaymentStatus.SUCCEEDED);
        }
        return PaymentResponse.from(paymentAttemptCloser.close(payment.getId(), null));
    }

    public void handleWebhook(VerifiedPaymentEvent event) {
        if (event.type() == WebhookEventType.IGNORED) {
            return;
        }

        Payment payment = paymentRepository.findFirstByProviderRef(event.providerRef())
                .orElse(null);

        if (payment == null) {
            log.error("No payment matches provider reference {}", event.providerRef());
            return;
        }

        switch (event.type()) {
            case PAYMENT_SUCCEEDED -> paymentAttemptCloser.settle(payment.getId());
            case PAYMENT_FAILED -> paymentAttemptCloser.close(payment.getId(), event.failureCode());
            case PAYMENT_CANCELED -> paymentAttemptCloser.close(payment.getId(), null);
            case IGNORED -> { }
        }
    }

    private void requireOwnedOrder(long userId, long orderId) {
        userAccess.requireExists(userId);
        if(!orderRepository.existsByIdAndUserId(orderId, userId)) {
            throw new OrderNotFoundException(orderId);
        }
    }
}
