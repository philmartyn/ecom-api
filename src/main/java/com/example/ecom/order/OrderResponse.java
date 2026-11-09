package com.example.ecom.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        OrderStatus status,
        BigDecimal subtotal,
        BigDecimal total,
        String currency,
        ShippingAddressResponse shipping,
        List<OrderItemResponse> items,
        int totalQuantity,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderResponse from(Order order){
        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getSubtotal(),
                order.getTotal(),
                order.getCurrency(),
                ShippingAddressResponse.from(order.getShippingAddress()),
                order.getItems().stream()
                        .map(OrderItemResponse::from)
                        .toList(),
                order.getTotalQuantity(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
