package com.example.ecom.auth;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}
