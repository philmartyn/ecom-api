package com.example.ecom.payment;

import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.common.exception.*;
import com.example.ecom.order.Order;
import com.example.ecom.order.OrderItem;
import com.example.ecom.order.OrderRepository;
import com.example.ecom.order.OrderStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaymentReservationService {

        static final String NO_PROVIDER = "none";

        private final OrderRepository orderRepository;
        private final ProductRepository productRepository;
        private final PaymentRepository paymentRepository;
        private final String provider;

        public PaymentReservationService(OrderRepository orderRepository,
                                         ProductRepository productRepository,
                                         PaymentRepository paymentRepository,
                                         @Value("${app.payments.provider:fake}") String provider) {
            this.orderRepository = orderRepository;
            this.productRepository = productRepository;
            this.paymentRepository = paymentRepository;
            this.provider = provider;
        }
    @Transactional
    public Payment reserve(long userId, long orderId) {
        Order order = orderRepository.findByIdAndUserIdForUpdate(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new OrderNotPayableException(order.getStatus());
        }

        if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.PENDING)) {
            throw new PaymentInProgressException(orderId);
        }

        List<OrderItem> items = order.getItems();
        Map<Long, Product> locked = lockProducts(items);

        rejectUnavailable(items, locked);
        items.forEach(item ->
                locked.get(item.getProduct().getId()).reserve(item.getQuantity()));

        String owner = order.getTotal().signum() == 0 ? NO_PROVIDER : provider;
        return paymentRepository.saveAndFlush(new Payment(order, owner));

    }

    private Map<Long, Product> lockProducts(List<OrderItem> items) {
        List<Long> ids = items.stream()
                .map(item -> item.getProduct().getId())
                .distinct()
                .sorted()
                .toList();

        return productRepository.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private void rejectUnavailable(List<OrderItem> items, Map<Long, Product> locked) {
        List<UnavailableItem> unavailableItems = new ArrayList<>();

        for (OrderItem item : items) {
            Product product = locked.get(item.getProduct().getId());

            if (product == null || !product.isActive()) {
                unavailableItems.add(new UnavailableItem(
                        item.getProduct().getId(),
                        item.getProduct().getSlug(),
                        UnavailableReason.INACTIVE));
            } else if (product.getStockQuantity() < item.getQuantity()) {
                unavailableItems.add(new UnavailableItem(
                        item.getProduct().getId(),
                        item.getProduct().getSlug(),
                        UnavailableReason.INSUFFICIENT_STOCK));
            }
        }

        if (!unavailableItems.isEmpty()) {
            throw new OrderUnavailableException(unavailableItems);
        }
    }




}
