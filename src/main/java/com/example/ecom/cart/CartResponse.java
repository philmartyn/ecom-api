package com.example.ecom.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record CartResponse(
        List<CartItemResponse> items,
        int totalQuantity,
        BigDecimal subtotal) {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public static CartResponse from(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(CartItemResponse::from)
                .toList();

        int totalQuantity = items.stream()
                .mapToInt(CartItemResponse::quantity)
                .sum();

        BigDecimal subtotal = items.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        return new CartResponse(items, totalQuantity, subtotal);
    }

    public static CartResponse empty() {
        return new CartResponse(List.of(), 0, ZERO);
    }
}
