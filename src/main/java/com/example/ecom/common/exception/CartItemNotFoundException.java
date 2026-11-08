package com.example.ecom.common.exception;

public class CartItemNotFoundException extends RuntimeException {

    public CartItemNotFoundException(Long productId) {
        super("No cart item with id " + productId);
    }
}
