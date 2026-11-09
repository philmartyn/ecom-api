package com.example.ecom.common.exception;

public class OrderNotFoundException extends RuntimeException{

    public OrderNotFoundException(long orderId) {
        super("Order " + orderId + " not found");
    }
}
