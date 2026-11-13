package com.example.ecom.payment;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    // Refunds out of scope until later slice
    REFUNDED
}
