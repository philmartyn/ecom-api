package com.example.ecom.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentCreatedResponse(
        Long id,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String failureCode,
        Instant createdAt,
        String clientSecret,
        String publishableKey) {

    public static PaymentCreatedResponse from(Payment payment,
                                               String clientSecret,
                                               String publishableKey){
        return new PaymentCreatedResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getFailureCode(),
                payment.getCreatedAt(),
                clientSecret,
                publishableKey);
    }
}
