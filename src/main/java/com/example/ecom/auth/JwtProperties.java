package com.example.ecom.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;

@ConfigurationProperties("app.jwt")
public record JwtProperties(
        String secret,
        Duration ttl,
        String issuer
) {
    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        int bytes = secret == null ? 0 : secret.getBytes(UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "app.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " bytes of UTF-8, but was " + bytes);
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("app.jwt.ttl must be a positive duration");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("app.jwt.issuer must be a non-blank string");
        }
    }
}

