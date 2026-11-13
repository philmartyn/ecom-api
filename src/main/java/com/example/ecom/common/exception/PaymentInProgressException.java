package com.example.ecom.common.exception;

public class PaymentInProgressException extends RuntimeException {
    public PaymentInProgressException(long orderId) {
        super("Order " + orderId + " already has a payment in progress");
    }
}
