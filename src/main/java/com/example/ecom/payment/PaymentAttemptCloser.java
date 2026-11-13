package com.example.ecom.payment;

import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.common.exception.PaymentNotFoundException;
import com.example.ecom.order.Order;
import com.example.ecom.order.OrderItem;
import com.example.ecom.order.OrderRepository;
import com.example.ecom.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaymentAttemptCloser {

    private static final Logger log = LoggerFactory.getLogger(PaymentAttemptCloser.class);

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public PaymentAttemptCloser(PaymentRepository paymentRepository,
                                OrderRepository orderRepository,
                                ProductRepository productRepository) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Payment attachProviderRef(Long paymentId, String providerRef) {
        Payment payment = lock(paymentId);
        payment.attachProviderRef(providerRef);
        return payment;
    }

    @Transactional
    public Payment close(Long paymentId, String failureCode) {
        Payment payment = lockOrderThenPayment(paymentId);

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return payment;
        }

        payment.markFailed(failureCode);
        restoreStock(payment.getOrder());
        return payment;
    }

    @Transactional
    public Payment settle(Long paymentId) {
        Payment payment = lockOrderThenPayment(paymentId);

        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            return payment;
        }

        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.error("Provider reports success for payment {} but it is {}",
                    paymentId, payment.getStatus());
            return payment;
        }

        payment.markSucceeded();

        Order order = payment.getOrder();
        if (order.getStatus() == OrderStatus.PENDING) {
            order.markPaid();
        } else if (order.getStatus() != OrderStatus.PAID) {
            log.error("Payment {} succeeded but order {} is {}",
                    paymentId, order.getId(), order.getStatus());
        }
        return payment;
    }

    private Payment lockOrderThenPayment(Long paymentId) {
        Long orderId = paymentRepository.findOrderIdById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        orderRepository.findByIdForUpdate(orderId);

        return lock(paymentId);
    }

    private Payment lock(Long paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private void restoreStock(Order order) {
        List<OrderItem> items = order.getItems();
        List<Long> ids = items.stream()
                .map(item -> item.getProduct().getId())
                .distinct()
                .sorted().
                toList();

        Map<Long, Product> locked = productRepository.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        items.forEach(item ->
                locked.get(item.getProduct().getId()).restore(item.getQuantity()));
    }

    /**
     * Used when the order itself is cancelled. Called from inside the order's
     * transaction, so it joins it rather than starting a second one.
     */
    @Transactional
    public Optional<Payment> closeOpenAttempt(long orderId, String failureCode) {
        return paymentRepository.findFirstByOrderIdAndStatus(orderId, PaymentStatus.PENDING)
                .map(payment -> close(payment.getId(), failureCode));
    }
}
