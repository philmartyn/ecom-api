package com.example.ecom.common.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String slug) {
        super("No product with slug " + slug);
    }
}
