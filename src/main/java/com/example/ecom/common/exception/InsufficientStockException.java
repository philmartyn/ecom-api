package com.example.ecom.common.exception;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException() {
        super("Not enough stock available");
    }
}
