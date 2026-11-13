package com.example.ecom.payment.webhook;

import com.example.ecom.common.exception.InvalidRequestException;
import com.example.ecom.common.exception.InvalidWebhookSignatureException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;


/**
 * The only authentication a public webhook has is its signature, so this class
 * verifies before it parses: no byte of the payload is trusted until the HMAC
 * matches.
 *
 * <p>Stripe signs {@code timestamp + "." + rawBody} with HMAC-SHA256 and sends
 * {@code t=<unix seconds>,v1=<hex>}. The timestamp is inside the signed string,
 * which is what stops a captured request being replayed later.</p>
 */
public class StripeWebhookVerifier {

    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final String ALGORITHM = "HmacSHA256";
    private static final String SIGNED_PAYLOAD = "%d.%s";

    private final String secret;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StripeWebhookVerifier(String secret) {
        this(secret, new ObjectMapper(), Clock.systemUTC());
    }

    StripeWebhookVerifier(String secret, ObjectMapper objectMapper, Clock clock) {
        this.secret = secret;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public VerifiedPaymentEvent verify(String payload, String signatureHeader) {
        verifySignature(payload, signatureHeader);
        return toEvent(payload);
    }

    private void verifySignature(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidWebhookSignatureException();
        }
        
        long timestamp = Long.MIN_VALUE;
        List<String> candidates = new ArrayList<>();
        
        for (String part : signatureHeader.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) {
                continue;
            }

            switch (pair[0].trim()) {
                case "t" -> timestamp = parseTimestamp(pair[1].trim());
                case "v1" -> candidates.add(pair[1].trim());
                default -> { /* v0, and anything Stripe adds later */ }
            }
        }

        if (timestamp == Long.MIN_VALUE || candidates.isEmpty()) {
            throw new InvalidWebhookSignatureException();
        }

        Instant signedAt = Instant.ofEpochSecond(timestamp);
        Duration age = Duration.between(signedAt, clock.instant()).abs();
        if (age.compareTo(TOLERANCE) > 0) {
            throw new InvalidWebhookSignatureException();
        }

        byte[] expected = sign(SIGNED_PAYLOAD.formatted(timestamp, payload));

        for (String candidate : candidates) {
            if (MessageDigest.isEqual(expected, decodeHex(candidate))) {
                return;
            }
        }
        throw new InvalidWebhookSignatureException();
    }

    private static long parseTimestamp(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new InvalidWebhookSignatureException();
        }
    }

    private static byte[] decodeHex(String value) {
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private byte[] sign(String signedPayload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private VerifiedPaymentEvent toEvent(String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Webhook payload is not valid JSON");
        }

        if (root == null || !root.isObject()) {
            throw new InvalidRequestException("Webhook payload is not valid JSON object");
        }

        WebhookEventType type = switch (root.path("type").asText("")) {
            case "payment_intent.succeeded" -> WebhookEventType.PAYMENT_SUCCEEDED;
            case "payment_intent.payment_failed" -> WebhookEventType.PAYMENT_FAILED;
            case "payment_intent.canceled" -> WebhookEventType.PAYMENT_CANCELED;
            default -> WebhookEventType.IGNORED;
        };

        JsonNode object = root.path("data").path("object");
        String providerRef = object.path("id").asText(null);

        if (type == WebhookEventType.IGNORED) {
            return new VerifiedPaymentEvent(providerRef, type, null);
        }
        if (providerRef == null || providerRef.isBlank()) {
            throw new InvalidRequestException("Webhook payload has no payment reference");
        }

        String failureCode = object.path("last_payment_error")
                .path("code")
                .asText(null);

        return new VerifiedPaymentEvent(providerRef, type, failureCode);
    }
}
