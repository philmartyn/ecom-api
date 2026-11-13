package com.example.ecom.payment.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Builds Stripe-shaped signature headers for tests, without the Stripe SDK.
 * <p>
 * Stripe signs {@code timestamp + "." + rawBody} with HMAC-SHA256 and sends
 * {@code t=<timestamp>,v1=<hex>}. Tests need the real algorithm, not a stub,
 * because the verifier is what is under test.
 */
public final class StripeSignatures {

    private StripeSignatures() {}

    public static String header(String secret, String payload, long timestamp) {
        return "t=" + timestamp + ",v1=" + signature(secret, payload, timestamp);
    }

    public static String headerWithV1(String timestamp, String... signatures) {
        return "t=" + timestamp + ",v1=" + String.join(",v1=", signatures);
    }

    public static String signature(String secret, String payload, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    (timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign test webhook payload", e);
        }
    }
}
