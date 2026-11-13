package com.example.ecom.payment.webhook;

import com.example.ecom.common.exception.InvalidRequestException;
import com.example.ecom.common.exception.InvalidWebhookSignatureException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The webhook gate: verify the signature over the raw body before trusting one
 * byte of it, then turn the Stripe event into the small domain event the
 * service understands.
 * <p>
 * The signature is the only authentication a public webhook has, so the tests
 * cover the ways it can be wrong: tampering, the wrong secret, a missing or
 * malformed header, a stale or future timestamp, and multiple candidate
 * signatures. Parsing failures are a {@code 400}; a valid but unhandled event
 * type is {@link WebhookEventType#IGNORED} rather than an error, because Stripe
 * retries anything that is not {@code 2xx}.
 */
class StripeWebhookVerifierTest {

    private static final String SECRET = "whsec_test_secret";

    private final StripeWebhookVerifier verifier = new StripeWebhookVerifier(SECRET);
    private final long now = Instant.now().getEpochSecond();

    // --- happy paths --------------------------------------------------------

    @Test
    void acceptsAValidSucceededEvent() {
        String payload = succeeded("pi_123");

        VerifiedPaymentEvent event = verifier.verify(
                payload, StripeSignatures.header(SECRET, payload, now));

        assertThat(event.providerRef()).isEqualTo("pi_123");
        assertThat(event.type()).isEqualTo(WebhookEventType.PAYMENT_SUCCEEDED);
        assertThat(event.failureCode()).isNull();
    }

    @Test
    void acceptsAValidFailureEventAndCarriesTheDeclineCode() {
        String payload = failed("pi_123", "card_declined");

        VerifiedPaymentEvent event = verifier.verify(
                payload, StripeSignatures.header(SECRET, payload, now));

        assertThat(event.providerRef()).isEqualTo("pi_123");
        assertThat(event.type()).isEqualTo(WebhookEventType.PAYMENT_FAILED);
        assertThat(event.failureCode()).isEqualTo("card_declined");
    }

    @Test
    void acceptsAValidCanceledEvent() {
        String payload = canceled("pi_123");

        VerifiedPaymentEvent event = verifier.verify(
                payload, StripeSignatures.header(SECRET, payload, now));

        assertThat(event.providerRef()).isEqualTo("pi_123");
        assertThat(event.type()).isEqualTo(WebhookEventType.PAYMENT_CANCELED);
        assertThat(event.failureCode()).isNull();
    }

    @Test
    void treatsAnUnrecognisedEventTypeAsIgnored() {
        String payload = """
                {
                  "id": "evt_4",
                  "type": "charge.refunded",
                  "data": {"object": {"id": "ch_123"}}
                }
                """;

        VerifiedPaymentEvent event = verifier.verify(
                payload, StripeSignatures.header(SECRET, payload, now));

        assertThat(event.type()).isEqualTo(WebhookEventType.IGNORED);
    }

    @Test
    void acceptsOneValidSignatureAmongSeveral() {
        String payload = succeeded("pi_123");

        String header = StripeSignatures.headerWithV1(
                String.valueOf(now),
                "deadbeef",
                StripeSignatures.signature(SECRET, payload, now));

        assertThat(verifier.verify(payload, header).providerRef()).isEqualTo("pi_123");
    }

    // --- signature failures -------------------------------------------------

    @Test
    void rejectsATamperedPayload() {
        String signed = succeeded("pi_123");
        String tampered = succeeded("pi_other");

        assertThatThrownBy(() -> verifier.verify(
                tampered, StripeSignatures.header(SECRET, signed, now)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void rejectsTheWrongSecret() {
        String payload = succeeded("pi_123");

        assertThatThrownBy(() -> verifier.verify(
                payload, StripeSignatures.header("whsec_other", payload, now)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void rejectsAMissingSignatureHeader() {
        String payload = succeeded("pi_123");

        assertThatThrownBy(() -> verifier.verify(payload, null))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(payload, ""))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void rejectsAMalformedSignatureHeader() {
        assertThatThrownBy(() -> verifier.verify(succeeded("pi_123"), "not-a-signature"))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(succeeded("pi_123"), "v1=deadbeef"))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void rejectsAHeaderWithAnInvalidV1Value() {
        String payload = succeeded("pi_123");

        assertThatThrownBy(() -> verifier.verify(
                payload, StripeSignatures.headerWithV1(
                        String.valueOf(now), "not-hex-at-all")))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void rejectsATimestampOutsideTheTolerance() {
        String payload = succeeded("pi_123");

        long stale = now - 600;
        long future = now + 600;

        assertThatThrownBy(() -> verifier.verify(
                payload, StripeSignatures.header(SECRET, payload, stale)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(
                payload, StripeSignatures.header(SECRET, payload, future)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    // --- payload failures ---------------------------------------------------

    @Test
    void rejectsAPayloadThatIsNotJson() {
        String payload = "{not json";

        assertThatThrownBy(() -> verifier.verify(
                payload, StripeSignatures.header(SECRET, payload, now)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsAnEventWithoutAProviderRef() {
        String payload = """
                {"id": "evt_5", "type": "payment_intent.succeeded", "data": {"object": {}}}
                """;

        assertThatThrownBy(() -> verifier.verify(
                payload, StripeSignatures.header(SECRET, payload, now)))
                .isInstanceOf(InvalidRequestException.class);
    }

    // --- payloads -----------------------------------------------------------

    static String succeeded(String providerRef) {
        return """
                {
                  "id": "evt_1",
                  "type": "payment_intent.succeeded",
                  "data": {"object": {"id": "%s", "object": "payment_intent",
                    "status": "succeeded"}}
                }
                """.formatted(providerRef);
    }

    static String failed(String providerRef, String failureCode) {
        return """
                {
                  "id": "evt_2",
                  "type": "payment_intent.payment_failed",
                  "data": {"object": {"id": "%s", "object": "payment_intent",
                    "status": "requires_payment_method",
                    "last_payment_error": {"code": "%s"}}}
                }
                """.formatted(providerRef, failureCode);
    }

    static String canceled(String providerRef) {
        return """
                {
                  "id": "evt_3",
                  "type": "payment_intent.canceled",
                  "data": {"object": {"id": "%s", "object": "payment_intent",
                    "status": "canceled"}}
                }
                """.formatted(providerRef);
    }
}
