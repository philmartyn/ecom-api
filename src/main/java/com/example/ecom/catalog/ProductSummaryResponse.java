package com.example.ecom.catalog;

import java.math.BigDecimal;

public record ProductSummaryResponse(
        Long id,
        String slug,
        String name,
        BigDecimal price,
        String imageUrl,
        CategorySummary category,
        boolean inStock) {

    public static ProductSummaryResponse from(Product product) {
        return new ProductSummaryResponse(
                product.getId(),
                product.getSlug(),
                product.getName(),
                product.getPrice(),
                product.getImageUrl(),
                CategorySummary.from(product.getCategory()),
                product.getStockQuantity() > 0);
    }
}
