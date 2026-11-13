package com.example.ecom.common.exception;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(long paymentId) {
        super("Payment " + paymentId + " not found");
    }
}

