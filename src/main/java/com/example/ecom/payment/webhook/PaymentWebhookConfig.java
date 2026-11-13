package com.example.ecom.payment.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration
public class PaymentWebhookConfig {

    /**
     * The verifier is a plain object so it can be unit-tested without a context.
     * The bean is registered here, and only demands a secret when a real
     * provider is selected, so {@code bootRun} against the fake still works.
     */
    @Bean
    StripeWebhookVerifier stripeWebhookVerifier(
            @Value("${app.payments.webhook-secret:}") String secret,
            @Value("${app.payments.provider:fake}") String provider) {

        if ("stripe".equals(provider)) {
            Assert.hasText(secret,
                    "app.payments.webhook-secret is required when app.payments.provider=stripe");
        }

        return new StripeWebhookVerifier(secret);
    }
}