package com.example.ecom.cart;

import com.example.ecom.catalog.Product;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CartItemResponse(
        CartProductResponse product,
        int quantity,
        BigDecimal lineTotal,
        boolean available) {
    public static CartItemResponse from(CartItem item) {
        Product product = item.getProduct();

        BigDecimal lineTotal = product.getPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP);

        boolean available = product.isActive()
                && product.getStockQuantity() >= item.getQuantity();

        return new CartItemResponse(
                CartProductResponse.from(product),
                item.getQuantity(),
                lineTotal,
                available);
    }
}
