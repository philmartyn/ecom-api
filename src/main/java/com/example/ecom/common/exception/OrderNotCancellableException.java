package com.example.ecom.common.exception;

public class OrderNotCancellableException extends RuntimeException {
    public OrderNotCancellableException(String status) {
        super("Order cannot be cancelled in status " + status);
    }
}
