package com.example.ecom.payment.webhook;

import com.example.ecom.common.exception.ApiExceptionHandler;
import com.example.ecom.payment.PaymentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public webhook endpoint through the web layer. The verifier is real, so
 * the signature is genuinely checked; the service is a mock, so this class only
 * pins the HTTP contract.
 * <p>
 * A valid event is {@code 200} and is handed to the service. Bad signatures and
 * missing headers are {@code 400} and never reach the service. Duplicate
 * deliveries, unknown references and unhandled event types are all {@code 200}:
 * the endpoint must not teach Stripe to retry a request that can never succeed.
 */
@WebMvcTest(PaymentWebhookController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandler.class, PaymentWebhookControllerTest.VerifierConfig.class})
class PaymentWebhookControllerTest {

    private static final String SECRET = "whsec_test_secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void acceptsAValidSucceededEventAndDelegatesIt() throws Exception {
        String payload = StripeWebhookVerifierTest.succeeded("pi_123");

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", signature(payload)))
                .andExpect(status().isOk());

        ArgumentCaptor<VerifiedPaymentEvent> event =
                ArgumentCaptor.forClass(VerifiedPaymentEvent.class);
        then(paymentService).should().handleWebhook(event.capture());
        assertThat(event.getValue().providerRef()).isEqualTo("pi_123");
        assertThat(event.getValue().type()).isEqualTo(WebhookEventType.PAYMENT_SUCCEEDED);
    }

    @Test
    void acceptsAValidFailureEventAndCarriesTheFailureCode() throws Exception {
        String payload = StripeWebhookVerifierTest.failed("pi_123", "card_declined");

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", signature(payload)))
                .andExpect(status().isOk());

        ArgumentCaptor<VerifiedPaymentEvent> event =
                ArgumentCaptor.forClass(VerifiedPaymentEvent.class);
        then(paymentService).should().handleWebhook(event.capture());
        assertThat(event.getValue().type()).isEqualTo(WebhookEventType.PAYMENT_FAILED);
        assertThat(event.getValue().failureCode()).isEqualTo("card_declined");
    }

    @Test
    void returns200ForAnUnrecognisedEventType() throws Exception {
        String payload = """
                {"id": "evt_9", "type": "charge.refunded", "data": {"object": {"id": "ch_1"}}}
                """;

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", signature(payload)))
                .andExpect(status().isOk());

        ArgumentCaptor<VerifiedPaymentEvent> event =
                ArgumentCaptor.forClass(VerifiedPaymentEvent.class);
        then(paymentService).should().handleWebhook(event.capture());
        assertThat(event.getValue().type()).isEqualTo(WebhookEventType.IGNORED);
    }

    @Test
    void acceptsADuplicateDeliveryTwice() throws Exception {
        String payload = StripeWebhookVerifierTest.succeeded("pi_123");
        String header = signature(payload);

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", header))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", header))
                .andExpect(status().isOk());

        then(paymentService).should(times(2)).handleWebhook(
                org.mockito.ArgumentMatchers.any(VerifiedPaymentEvent.class));
    }

    @Test
    void acceptsAnUnknownProviderReferenceWith200() throws Exception {
        String payload = StripeWebhookVerifierTest.succeeded("pi_does_not_exist");

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", signature(payload)))
                .andExpect(status().isOk());
    }

    @Test
    void readsTheRawBodySoWhitespaceSurvivesVerification() throws Exception {
        String payload = """
                {
                  "id": "evt_1",
                  "type": "payment_intent.succeeded",
                  "data": {"object": {"id": "pi_123", "object": "payment_intent"}}
                }
                """;

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", signature(payload)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsABadSignatureWithoutCallingTheService() throws Exception {
        String payload = StripeWebhookVerifierTest.succeeded("pi_123");

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", StripeSignatures.header(
                                "whsec_other", payload, Instant.now().getEpochSecond())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Invalid webhook signature"));

        then(paymentService).shouldHaveNoInteractions();
    }

    @Test
    void rejectsAMissingSignatureHeader() throws Exception {
        String payload = StripeWebhookVerifierTest.succeeded("pi_123");

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid webhook signature"));

        then(paymentService).shouldHaveNoInteractions();
    }

    private static String signature(String payload) {
        return StripeSignatures.header(
                SECRET, payload, Instant.now().getEpochSecond());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class VerifierConfig {

        @Bean
        StripeWebhookVerifier stripeWebhookVerifier() {
            return new StripeWebhookVerifier(SECRET);
        }
    }
}
