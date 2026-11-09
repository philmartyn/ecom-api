package com.example.ecom.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.example.ecom.common.helpers.Numbers.BIG_DECIMAL_ZERO;

public record CartResponse(
        List<CartItemResponse> items,
        int totalQuantity,
        BigDecimal subtotal) {

    public static CartResponse from(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(CartItemResponse::from)
                .toList();

        int totalQuantity = items.stream()
                .mapToInt(CartItemResponse::quantity)
                .sum();

        BigDecimal subtotal = items.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BIG_DECIMAL_ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        return new CartResponse(items, totalQuantity, subtotal);
    }

    public static CartResponse empty() {
        return new CartResponse(List.of(), 0, BIG_DECIMAL_ZERO);
    }
}
