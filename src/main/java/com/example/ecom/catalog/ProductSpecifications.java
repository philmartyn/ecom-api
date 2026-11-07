package com.example.ecom.catalog;

import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

final class ProductSpecifications {
    private ProductSpecifications() {}

    static Specification<Product> matching(ProductQuery query) {
        Specification<Product> spec =
                (root, criteriaQuery, cb) -> cb.isTrue(root.get("active"));

        if (query.category() != null) {
            spec = spec.and((root, criteriaQuery, cb) ->
                     cb.equal(root.get("category").get("slug"), query.category()));
        }

        if (query.q() != null) {
            String pattern = "%" + escapeLike(
                    query.q().toLowerCase(Locale.ROOT)) + "%";

            spec = spec.and((root, criteriaQuery, cb) ->
                    cb.like(cb.lower(root.get("name")), pattern, '\\'));

        }
        return spec;

    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
