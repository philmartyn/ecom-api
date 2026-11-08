package com.example.ecom.cart;

import com.example.ecom.catalog.Product;

import java.math.BigDecimal;

public record CartProductResponse(
        Long id,
        String slug,
        String name,
        BigDecimal price,
        String imageUrl) {
    public static CartProductResponse from(Product product) {
        return new CartProductResponse(
                product.getId(),
                product.getSlug(),
                product.getName(),
                product.getPrice(),
                product.getImageUrl());
    }
}
