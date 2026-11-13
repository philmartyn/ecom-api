package com.example.ecom.payment;

import com.example.ecom.auth.CurrentUserProvider;
import com.example.ecom.common.web.NoStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class PaymentController {

    private final PaymentService paymentService;
    private final CurrentUserProvider currentUserProvider;

    public PaymentController(PaymentService paymentService,
                             CurrentUserProvider currentUserProvider) {
        this.paymentService = paymentService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/{orderId}/payments")
    public ResponseEntity<PaymentCreatedResponse> create(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @PathVariable long orderId) {
        PaymentCreatedResponse payment = paymentService.create(
                currentUserProvider.currentUserId(jwt),
                orderId);

        return NoStore.created(URI.create("/api/orders/" + orderId + "/payments/" + payment.id()))
                .body(payment);
    }

    @GetMapping("/{orderId}/payments")
    public ResponseEntity<List<PaymentResponse>> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @PathVariable long orderId){
        return NoStore.ok().body(paymentService.list(
                currentUserProvider.currentUserId(jwt), orderId));
    }

    @GetMapping("/{orderId}/payments/{paymentId}")
    public ResponseEntity<PaymentResponse> get(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @PathVariable long orderId,
            @PathVariable long paymentId){
        return NoStore.ok().body(paymentService.get(
                currentUserProvider.currentUserId(jwt), orderId, paymentId));
    }

    @PostMapping("/{orderId}/payments/{paymentId}/cancel")
    public ResponseEntity<PaymentResponse> cancel(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @PathVariable long orderId,
            @PathVariable long paymentId){
        return NoStore.ok().body(paymentService.cancel(
                currentUserProvider.currentUserId(jwt), orderId, paymentId));
    }
}
