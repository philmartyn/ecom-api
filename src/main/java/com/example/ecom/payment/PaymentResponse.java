package com.example.ecom.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long id,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String failureCode,
        Instant createdAt) {

    public static PaymentResponse from(Payment payment){
    return new PaymentResponse(
            payment.getId(),
            payment.getStatus(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getFailureCode(),
            payment.getCreatedAt());
    }
}
