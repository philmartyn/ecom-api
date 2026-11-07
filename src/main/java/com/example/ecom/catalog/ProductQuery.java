package com.example.ecom.catalog;

import com.example.ecom.common.exception.InvalidRequestException;

import java.util.Locale;
import java.util.Set;

/**
 * One validated, normalised catalog query. The controller binds raw strings
 * and ints; this record is the boundary between "HTTP input" and "something
 * safe to hand to a repository".
 */
public record ProductQuery(
        String q,
        String category,
        int page,
        int size,
        String sort) {

    private static final Set<String> SORTABLE_FIELDS =
            Set.of("name", "price", "createdAt");

    public ProductQuery {
        q = normalise(q);
        category = normalise(category);

        if (page < 0) {
            throw new InvalidRequestException("page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw new InvalidRequestException("size must be between 1 and 100");
        }

        sort = parseSort(sort);
    }

    public String sortField() {
        return sort.substring(0, sort.indexOf(','));
    }

    public boolean ascending() {
        return sort.endsWith(",asc");
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String parseSort(String value) {
        if (value == null || value.isBlank()) {
            return "name,asc";
        }

        String[] parts = value.trim().split(",", -1);
        if (parts.length > 2) {
            throw new InvalidRequestException("sort must be '<field>,<asc|desc>'");
        }

        String field = parts[0].trim();
        if (!SORTABLE_FIELDS.contains(field)) {
            throw new InvalidRequestException("sort field must be one of " + SORTABLE_FIELDS);
        }

        String direction = parts.length == 2
                ? parts[1].trim().toLowerCase(Locale.ROOT)
                : "asc";

        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw new InvalidRequestException("sort direction must be asc or desc");
        }

        return field + "," + direction;
    }
}
