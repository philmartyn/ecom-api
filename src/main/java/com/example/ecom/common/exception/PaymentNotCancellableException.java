package com.example.ecom.common.exception;

import com.example.ecom.payment.PaymentStatus;

public class PaymentNotCancellableException extends RuntimeException {
    public PaymentNotCancellableException(PaymentStatus status) {
        super("Payment cannot be cancelled in status" + status);
    }
}
