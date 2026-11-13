package com.example.ecom.payment.gateway;

public record GatewayIntent(
        String providerRef,
        GatewayStatus status,
        String clientSecret,
        String failureCode) {

}
