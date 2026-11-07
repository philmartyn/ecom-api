package com.example.ecom.catalog;

public record CategorySummary(String slug, String name) {

    public static CategorySummary from(Category category) {
        return new CategorySummary(category.getSlug(), category.getName());
    }
}
