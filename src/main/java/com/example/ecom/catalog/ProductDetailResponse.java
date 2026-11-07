package com.example.ecom.catalog;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductDetailResponse(
        Long id,
        String slug,
        String name,
        BigDecimal price,
        String imageUrl,
        CategorySummary category,
        boolean inStock,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductDetailResponse from(Product product) {
        return new ProductDetailResponse(
                product.getId(),
                product.getSlug(),
                product.getName(),
                product.getPrice(),
                product.getImageUrl(),
                CategorySummary.from(product.getCategory()),
                product.getStockQuantity() > 0,
                product.getDescription(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
