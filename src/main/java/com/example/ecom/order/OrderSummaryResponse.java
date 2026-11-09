package com.example.ecom.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderSummaryResponse(
        Long id,
        OrderStatus status,
        BigDecimal subtotal,
        BigDecimal total,
        String currency,
        int totalQuantity,
        Instant createdAt) {

    public static OrderSummaryResponse from(Order order){
        return new OrderSummaryResponse(
                order.getId(),
                order.getStatus(),
                order.getSubtotal(),
                order.getTotal(),
                order.getCurrency(),
                order.getTotalQuantity(),
                order.getCreatedAt());
    }
}
