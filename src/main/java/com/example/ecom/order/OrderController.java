package com.example.ecom.order;

import com.example.ecom.auth.CurrentUserProvider;
import com.example.ecom.common.web.NoStore;
import com.example.ecom.common.web.PageResponse;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final CurrentUserProvider currentUserProvider;

    public OrderController(OrderService orderService,
                           CurrentUserProvider currentUserProvider) {
        this.orderService = orderService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> checkout(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @Valid @RequestBody CheckoutRequest request) {
        OrderResponse order = orderService.checkout(
                currentUserProvider.currentUserId(jwt),
                request.shipping().toDomain());

        return NoStore.created(URI.create("/api/orders/" + order.id()))
                .body(order);
    }

    @GetMapping
    public ResponseEntity<PageResponse<OrderSummaryResponse>> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size){
        return NoStore.ok().body(orderService.listOrders(
                currentUserProvider.currentUserId(jwt), page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> detail(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @PathVariable long id) {
        return NoStore.ok().body(orderService.getOrder(
                currentUserProvider.currentUserId(jwt), id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancel(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @PathVariable long id) {
        return NoStore.ok().body(orderService.cancel(
                currentUserProvider.currentUserId(jwt), id));
    }
}
