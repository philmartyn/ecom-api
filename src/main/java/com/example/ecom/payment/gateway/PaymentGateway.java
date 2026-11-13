package com.example.ecom.payment.gateway;

import com.example.ecom.payment.Payment;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public interface PaymentGateway {
    GatewayIntent create(Payment payment);
    GatewayIntent retrieve(String providerRef);
    GatewayIntent cancel(String providerRef);
    Optional<String> publishableKey();
}
