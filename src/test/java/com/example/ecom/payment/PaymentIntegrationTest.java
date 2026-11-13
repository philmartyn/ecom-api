package com.example.ecom.payment;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.CategoryRepository;
import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.payment.gateway.FakePaymentGateway;
import com.example.ecom.payment.gateway.PaymentGateway;
import com.example.ecom.payment.webhook.StripeSignatures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payment slice end to end: real filter chain, real tokens, real controller
 * and service, real PostgreSQL, and the fake provider.
 * <p>
 * There is deliberately no class-level {@code @Transactional}. Opening an
 * attempt reserves stock in a transaction that must actually commit before the
 * provider is called, and each HTTP request is its own transaction. The fixture
 * is removed in {@code @AfterEach}, so the shared container stays clean.
 * <p>
 * What is pinned here: the reservation happens before the provider call, a
 * success webhook makes it permanent, a failure/cancel/expiry restores it
 * exactly once, the second attempt while one is open is {@code 409}, a
 * provider failure compensates, and the response never leaks provider state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String WEBHOOK_SECRET = "whsec_test_secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PaymentGateway paymentGateway;

    private Long categoryId;
    private Long productId;
    private Long userId;
    private String productSlug;
    private String token;

    @BeforeEach
    void seed() throws Exception {
        String suffix = UUID.randomUUID().toString();

        Category category = categoryRepository.saveAndFlush(
                new Category("Payments " + suffix, "payments-" + suffix, "Payment tests"));
        categoryId = category.getId();

        productSlug = "mechanical-keyboard-" + suffix;
        Product product = productRepository.saveAndFlush(new Product(
                category, "SKU-PAY-" + suffix, "Mechanical Keyboard", productSlug,
                "Clicky keys", new BigDecimal("89.99"), 10, null));
        productId = product.getId();

        String email = "pay-" + suffix + "@example.com";
        userId = register(email);
        token = login(email);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("""
                DELETE FROM orders
                WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'pay-%@example.com')
                """);
        jdbc.update("DELETE FROM users WHERE email LIKE 'pay-%@example.com'");
        if (productId != null) {
            jdbc.update("DELETE FROM products WHERE id = ?", productId);
        }
        if (categoryId != null) {
            jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        }
    }

    // --- create -------------------------------------------------------------

    @Test
    void createReservesStockAndReturnsAClientSecret() throws Exception {
        long orderId = checkout(2);
        assertThat(stock()).isEqualTo(10);

        String response = createPayment(orderId);

        assertThat((String) JsonPath.read(response, "$.status")).isEqualTo("PENDING");
        assertThat((String) JsonPath.read(response, "$.clientSecret")).isNotBlank();
        assertThat((String) JsonPath.read(response, "$.publishableKey")).isNull();
        assertThat(((Number) JsonPath.read(response, "$.amount")).doubleValue())
                .isEqualTo(179.98);
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(8);
        assertThat(paymentCount(orderId)).isEqualTo(1);
    }

    @Test
    void aSecondCreateWhileAnAttemptIsOpenIs409() throws Exception {
        long orderId = checkout(2);
        createPayment(orderId);

        mockMvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Payment already in progress"));

        assertThat(stock()).isEqualTo(8);
        assertThat(paymentCount(orderId)).isEqualTo(1);
    }

    @Test
    void createOnANonPendingOrderIs409() throws Exception {
        long orderId = checkout(2);
        long paymentId = paymentId(createPayment(orderId));
        sendSucceeded(providerRef(paymentId)).andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order is not payable"));
    }

    @Test
    void createWhenTheProductIsNoLongerActiveNamesIt() throws Exception {
        long orderId = checkout(2);
        jdbc.update("UPDATE products SET active = FALSE WHERE id = ?", productId);

        mockMvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order items are unavailable"))
                .andExpect(jsonPath("$.unavailableItems[0].productId")
                        .value(productId.intValue()))
                .andExpect(jsonPath("$.unavailableItems[0].slug").value(productSlug))
                .andExpect(jsonPath("$.unavailableItems[0].reason").value("INACTIVE"))
                .andExpect(jsonPath("$.unavailableItems[0].stockQuantity").doesNotExist());

        assertThat(stock()).isEqualTo(10);
        assertThat(paymentCount(orderId)).isZero();
    }

    @Test
    void createWithInsufficientStockNamesIt() throws Exception {
        long orderId = checkout(2);
        jdbc.update("UPDATE products SET stock_quantity = 1 WHERE id = ?", productId);

        mockMvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableItems[0].productId")
                        .value(productId.intValue()))
                .andExpect(jsonPath("$.unavailableItems[0].slug").value(productSlug))
                .andExpect(jsonPath("$.unavailableItems[0].reason")
                        .value("INSUFFICIENT_STOCK"));

        assertThat(stock()).isEqualTo(1);
        assertThat(paymentCount(orderId)).isZero();
    }

    @Test
    void aProviderFailureRestoresTheReservationAndReturns502() throws Exception {
        long orderId = checkout(2);
        fakeGateway().failNextCreate("processing_error");

        mockMvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Payment provider unavailable"));

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(10);
        assertThat(paymentCount(orderId)).isEqualTo(1);
        assertThat(paymentStatus(onlyPaymentId(orderId))).isEqualTo("FAILED");
    }

    @Test
    void aZeroTotalOrderSettlesImmediatelyWithoutAProvider() throws Exception {
        jdbc.update("UPDATE products SET price = 0 WHERE id = ?", productId);
        long orderId = checkout(1);

        String response = createPayment(orderId);
        long paymentId = paymentId(response);

        assertThat((String) JsonPath.read(response, "$.status")).isEqualTo("SUCCEEDED");
        assertThat((String) JsonPath.read(response, "$.clientSecret")).isNull();
        assertThat((String) JsonPath.read(response, "$.publishableKey")).isNull();
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(stock()).isEqualTo(9);
        assertThat(providerOf(paymentId)).isEqualTo("none");
        assertThat(providerRef(paymentId)).isNull();
        assertThat(amountOf(paymentId)).isEqualByComparingTo("0.00");
    }

    // --- webhook success ----------------------------------------------------

    @Test
    void aSucceededWebhookMarksTheOrderPaidAndKeepsTheReservation() throws Exception {
        long orderId = checkout(2);
        long paymentId = paymentId(createPayment(orderId));
        String providerRef = providerRef(paymentId);

        sendSucceeded(providerRef).andExpect(status().isOk());

        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(stock()).isEqualTo(8);

        // A duplicate delivery is a no-op, not a second decrement or a release.
        sendSucceeded(providerRef).andExpect(status().isOk());

        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(stock()).isEqualTo(8);
        assertThat(succeededPaymentCount(orderId)).isEqualTo(1);
    }

    // --- webhook failure and cancellation ----------------------------------

    @Test
    void aFailedWebhookReleasesTheReservationAndLeavesTheOrderPending() throws Exception {
        long orderId = checkout(2);
        long paymentId = paymentId(createPayment(orderId));

        sendFailed(providerRef(paymentId), "card_declined").andExpect(status().isOk());

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(paymentStatus(paymentId)).isEqualTo("FAILED");
        assertThat(failureCode(paymentId)).isEqualTo("card_declined");
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void aCanceledWebhookReleasesTheReservation() throws Exception {
        long orderId = checkout(2);
        long paymentId = paymentId(createPayment(orderId));

        sendCanceled(providerRef(paymentId)).andExpect(status().isOk());

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(paymentStatus(paymentId)).isEqualTo("FAILED");
        assertThat(failureCode(paymentId)).isNull();
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void aNewAttemptCanFollowAFailedOne() throws Exception {
        long orderId = checkout(2);
        long first = paymentId(createPayment(orderId));
        sendFailed(providerRef(first), "card_declined").andExpect(status().isOk());

        long second = paymentId(createPayment(orderId));

        assertThat(paymentCount(orderId)).isEqualTo(2);
        assertThat(paymentStatus(first)).isEqualTo("FAILED");
        assertThat(paymentStatus(second)).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void cancellingAnAttemptReleasesTheReservationAndIsIdempotent() throws Exception {
        long orderId = checkout(2);
        long paymentId = paymentId(createPayment(orderId));

        mockMvc.perform(post("/api/orders/" + orderId + "/payments/" + paymentId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertThat(stock()).isEqualTo(10);
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");

        mockMvc.perform(post("/api/orders/" + orderId + "/payments/" + paymentId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void cancellingASucceededAttemptIs409() throws Exception {
        long orderId = checkout(2);
        long paymentId = paymentId(createPayment(orderId));
        sendSucceeded(providerRef(paymentId)).andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/" + orderId + "/payments/" + paymentId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Payment cannot be cancelled"));

        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void cancellingTheOrderReleasesTheOpenAttempt() throws Exception {
        long orderId = checkout(2);
        long paymentId = paymentId(createPayment(orderId));

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(paymentStatus(paymentId)).isEqualTo("FAILED");
        assertThat(stock()).isEqualTo(10);
    }

    // --- list ---------------------------------------------------------------

    @Test
    void listReturnsOnlyThatOrdersAttemptsNewestFirstWithoutSecrets() throws Exception {
        long orderId = checkout(2);
        long first = paymentId(createPayment(orderId));
        sendFailed(providerRef(first), "card_declined").andExpect(status().isOk());
        long second = paymentId(createPayment(orderId));

        mockMvc.perform(get("/api/orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$[0].publishableKey").doesNotExist())
                .andExpect(jsonPath("$[0].providerRef").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].failureCode").value("card_declined"));
    }

    // --- ownership and unknown resources -----------------------------------

    @Test
    void anotherUserCannotCreateListOrCancelAPayment() throws Exception {
        long orderId = checkout(2);
        long paymentId = paymentId(createPayment(orderId));
        String bobEmail = "pay-bob-" + UUID.randomUUID() + "@example.com";
        register(bobEmail);
        String bobToken = login(bobEmail);

        mockMvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));

        mockMvc.perform(get("/api/orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));

        mockMvc.perform(post("/api/orders/" + orderId + "/payments/" + paymentId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void anUnknownOrderIs404() throws Exception {
        mockMvc.perform(post("/api/orders/999999/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void anUnknownPaymentOnAKnownOrderIs404() throws Exception {
        long orderId = checkout(1);

        mockMvc.perform(post("/api/orders/" + orderId + "/payments/999999/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Payment not found"));
    }

    // --- webhook edge cases -------------------------------------------------

    @Test
    void aBadWebhookSignatureIs400AndChangesNothing() throws Exception {
        long orderId = checkout(2);
        long paymentId = paymentId(createPayment(orderId));
        String payload = succeededEvent(providerRef(paymentId));

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", "t=1,v1=deadbeef"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Invalid webhook signature"));

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
    }

    @Test
    void anUnknownProviderReferenceIs200AndChangesNothing() throws Exception {
        long orderId = checkout(2);
        long paymentId = paymentId(createPayment(orderId));

        sendSucceeded("pi_does_not_exist").andExpect(status().isOk());

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(8);
    }

    // --- HTTP helpers -------------------------------------------------------

    private long checkout(int quantity) throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": %d, "quantity": %d}
                                """.formatted(productId, quantity)))
                .andExpect(status().isOk());

        String response = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shipping":{"name":"Bob Smith","line1":"1 Main St",\
                                "line2":"Apt 2","city":"Springfield",\
                                "postalCode":"12345","country":"US"}}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private String createPayment(long orderId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Location"))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private ResultActions sendSucceeded(String providerRef) throws Exception {
        return sendWebhook(succeededEvent(providerRef));
    }

    private ResultActions sendFailed(String providerRef, String failureCode) throws Exception {
        return sendWebhook(failedEvent(providerRef, failureCode));
    }

    private ResultActions sendCanceled(String providerRef) throws Exception {
        return sendWebhook(canceledEvent(providerRef));
    }

    private ResultActions sendWebhook(String payload) throws Exception {
        return mockMvc.perform(post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("Stripe-Signature", StripeSignatures.header(
                        WEBHOOK_SECRET, payload, Instant.now().getEpochSecond())));
    }

    private static String succeededEvent(String providerRef) {
        return """
                {"id":"evt_%s","type":"payment_intent.succeeded",\
                "data":{"object":{"id":"%s","object":"payment_intent","status":"succeeded"}}}
                """.formatted(UUID.randomUUID(), providerRef);
    }

    private static String failedEvent(String providerRef, String failureCode) {
        return """
                {"id":"evt_%s","type":"payment_intent.payment_failed",\
                "data":{"object":{"id":"%s","object":"payment_intent",\
                "status":"requires_payment_method",\
                "last_payment_error":{"code":"%s"}}}}
                """.formatted(UUID.randomUUID(), providerRef, failureCode);
    }

    private static String canceledEvent(String providerRef) {
        return """
                {"id":"evt_%s","type":"payment_intent.canceled",\
                "data":{"object":{"id":"%s","object":"payment_intent","status":"canceled"}}}
                """.formatted(UUID.randomUUID(), providerRef);
    }

    // --- registration helpers ----------------------------------------------

    private long register(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s",\
                                "firstName":"Alice","lastName":"Smith"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(response, "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // --- database helpers ---------------------------------------------------

    private int stock() {
        return jdbc.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class, productId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private long paymentCount(long orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE order_id = ?", Long.class, orderId);
    }

    private long succeededPaymentCount(long orderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM payments
                WHERE order_id = ? AND status = 'SUCCEEDED'
                """, Long.class, orderId);
    }

    private long onlyPaymentId(long orderId) {
        return jdbc.queryForObject(
                "SELECT id FROM payments WHERE order_id = ?", Long.class, orderId);
    }

    private String paymentStatus(long paymentId) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String failureCode(long paymentId) {
        return jdbc.queryForObject(
                "SELECT failure_code FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String providerOf(long paymentId) {
        return jdbc.queryForObject(
                "SELECT provider FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String providerRef(long paymentId) {
        return jdbc.queryForObject(
                "SELECT provider_ref FROM payments WHERE id = ?", String.class, paymentId);
    }

    private BigDecimal amountOf(long paymentId) {
        return jdbc.queryForObject(
                "SELECT amount FROM payments WHERE id = ?", BigDecimal.class, paymentId);
    }

    private static long paymentId(String response) {
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private FakePaymentGateway fakeGateway() {
        return (FakePaymentGateway) paymentGateway;
    }
}
