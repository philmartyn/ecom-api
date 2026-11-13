package com.example.ecom.payment.webhook;

import com.example.ecom.payment.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated, and the one route that must not require a JWT:
 * Stripe has no user token.
 *
 * <p>The body is read as a {@code String} on purpose. Signature verification is
 * over the exact bytes Stripe sent; binding to a DTO and re-serialising would
 * change the whitespace and invalidate every signature.</p>
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentWebhookController {

    private final PaymentService paymentService;
    private final StripeWebhookVerifier verifier;

    public PaymentWebhookController(PaymentService paymentService,
                                    StripeWebhookVerifier verifier) {
        this.paymentService = paymentService;
        this.verifier = verifier;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(
            @RequestBody String payload,
            @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        paymentService.handleWebhook(verifier.verify(payload, signature));
        return ResponseEntity.ok().build();
    }
}