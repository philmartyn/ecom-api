package com.example.ecom.payment.webhook;

public record VerifiedPaymentEvent(
     String providerRef,
     WebhookEventType type,
     String failureCode) {
}

