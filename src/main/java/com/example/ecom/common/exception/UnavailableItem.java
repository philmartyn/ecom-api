package com.example.ecom.common.exception;

public record UnavailableItem(
        Long productId,
        String slug,
        UnavailableReason reason) {
}
