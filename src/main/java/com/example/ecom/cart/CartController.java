package com.example.ecom.cart;

import com.example.ecom.auth.CurrentUserProvider;
import com.example.ecom.common.web.NoStore;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;
    private final CurrentUserProvider currentUserProvider;

    public CartController(CartService cartService,
                          CurrentUserProvider currentUserProvider) {
        this.cartService = cartService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public ResponseEntity<CartResponse> getCart(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt) {
        return NoStore.ok().body(cartService.getCart(currentUserProvider.currentUserId(jwt)));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @Valid @RequestBody AddCartItemRequest request) {
        return  NoStore.ok().body(cartService.addItem(
                    currentUserProvider.currentUserId(jwt),
                    request.productId(),
                    request.quantity()));
    }

    @PatchMapping("/items/{productId}")
    public ResponseEntity<CartResponse> updateItem(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @PathVariable long productId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return  NoStore.ok().body(cartService.changeQuantity(
                    currentUserProvider.currentUserId(jwt),
                    productId,
                    request.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<CartResponse> removeItem(
            @AuthenticationPrincipal(errorOnInvalidType = true) Jwt jwt,
            @PathVariable long productId) {
        return  NoStore.ok().body(cartService.removeItem(
                    currentUserProvider.currentUserId(jwt),
                    productId));
    }
}
