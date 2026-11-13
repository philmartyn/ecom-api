package com.example.ecom.payment;

import com.example.ecom.auth.CurrentUserProvider;
import com.example.ecom.common.exception.ApiExceptionHandler;
import com.example.ecom.common.exception.OrderNotPayableException;
import com.example.ecom.common.exception.OrderNotFoundException;
import com.example.ecom.common.exception.OrderUnavailableException;
import com.example.ecom.common.exception.PaymentInProgressException;
import com.example.ecom.common.exception.PaymentNotCancellableException;
import com.example.ecom.common.exception.PaymentNotFoundException;
import com.example.ecom.common.exception.PaymentProviderException;
import com.example.ecom.common.exception.UnavailableItem;
import com.example.ecom.common.exception.UnavailableReason;
import com.example.ecom.common.exception.UserNotFoundException;
import com.example.ecom.order.OrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice for the payment endpoints. The controller, Jackson and the
 * exception advice are real; the service is a mock.
 * <p>
 * The contract pinned here is deliberately narrow: create is {@code 201} with a
 * {@code Location} and the only appearance of {@code clientSecret} and
 * {@code publishableKey}; list and get are unpaginated reads with neither secret
 * nor {@code providerRef}; every response is {@code Cache-Control: no-store}; and
 * state conflicts are {@code 409}, provider failures are {@code 502}, and
 * anything the caller does not own is {@code 404}.
 */
@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandler.class, CurrentUserProvider.class})
class PaymentControllerTest {

    private static final long USER_ID = 42L;
    private static final long ORDER_ID = 7L;
    private static final long PAYMENT_ID = 11L;
    private static final Instant CREATED = Instant.parse("2026-11-09T12:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("179.98");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- create -------------------------------------------------------------

    @Test
    void createReturns201WithLocationNoStoreAndTheClientSecret() throws Exception {
        authenticate();
        given(paymentService.create(USER_ID, ORDER_ID)).willReturn(createdResponse());

        mockMvc.perform(post("/api/orders/7/payments"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/7/payments/11"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value((int) PAYMENT_ID))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(179.98))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value("2026-11-09T12:00:00Z"))
                .andExpect(jsonPath("$.clientSecret").value("cs_test_secret"))
                .andExpect(jsonPath("$.publishableKey").value("pk_test_public"))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.providerRef").doesNotExist());

        then(paymentService).should().create(USER_ID, ORDER_ID);
    }

    @Test
    void createForAZeroTotalOrderReturnsASucceededAttemptWithoutASecret() throws Exception {
        authenticate();
        given(paymentService.create(USER_ID, ORDER_ID)).willReturn(new PaymentCreatedResponse(
                PAYMENT_ID, PaymentStatus.SUCCEEDED, new BigDecimal("0.00"), "USD",
                null, CREATED, null, null));

        mockMvc.perform(post("/api/orders/7/payments"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.amount").value(0.00))
                .andExpect(jsonPath("$.clientSecret").value(nullValue()))
                .andExpect(jsonPath("$.publishableKey").value(nullValue()));
    }

    @Test
    void createMapsAnUnknownOrForeignOrderTo404() throws Exception {
        authenticate();
        willThrow(new OrderNotFoundException(ORDER_ID))
                .given(paymentService).create(USER_ID, ORDER_ID);

        mockMvc.perform(post("/api/orders/7/payments"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void createMapsANonPendingOrderTo409() throws Exception {
        authenticate();
        willThrow(new OrderNotPayableException(OrderStatus.PAID))
                .given(paymentService).create(USER_ID, ORDER_ID);

        mockMvc.perform(post("/api/orders/7/payments"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order is not payable"));
    }

    @Test
    void createMapsAnOpenAttemptTo409() throws Exception {
        authenticate();
        willThrow(new PaymentInProgressException(ORDER_ID))
                .given(paymentService).create(USER_ID, ORDER_ID);

        mockMvc.perform(post("/api/orders/7/payments"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Payment already in progress"));
    }

    @Test
    void createMapsUnavailableOrderItemsTo409WithoutLeakingStock() throws Exception {
        authenticate();
        willThrow(new OrderUnavailableException(List.of(
                new UnavailableItem(1L, "mechanical-keyboard", UnavailableReason.INSUFFICIENT_STOCK))))
                .given(paymentService).create(USER_ID, ORDER_ID);

        mockMvc.perform(post("/api/orders/7/payments"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order items are unavailable"))
                .andExpect(jsonPath("$.detail").value("One or more items are unavailable"))
                .andExpect(jsonPath("$.unavailableItems[0].productId").value(1))
                .andExpect(jsonPath("$.unavailableItems[0].slug").value("mechanical-keyboard"))
                .andExpect(jsonPath("$.unavailableItems[0].reason").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.unavailableItems[0].stockQuantity").doesNotExist());
    }

    @Test
    void createMapsAProviderFailureTo502() throws Exception {
        authenticate();
        willThrow(new PaymentProviderException("stripe"))
                .given(paymentService).create(USER_ID, ORDER_ID);

        mockMvc.perform(post("/api/orders/7/payments"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Payment provider unavailable"));
    }

    // --- list ---------------------------------------------------------------

    @Test
    void listReturnsTheAttemptsWithoutSecretsOrProviderReferences() throws Exception {
        authenticate();
        given(paymentService.list(USER_ID, ORDER_ID)).willReturn(List.of(
                response(PaymentStatus.PENDING, null),
                response(PaymentStatus.FAILED, "card_declined")));

        mockMvc.perform(get("/api/orders/7/payments"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value((int) PAYMENT_ID))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].amount").value(179.98))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].failureCode").value(nullValue()))
                .andExpect(jsonPath("$[0].createdAt").value("2026-11-09T12:00:00Z"))
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$[0].publishableKey").doesNotExist())
                .andExpect(jsonPath("$[0].provider").doesNotExist())
                .andExpect(jsonPath("$[0].providerRef").doesNotExist())
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].failureCode").value("card_declined"));

        then(paymentService).should().list(USER_ID, ORDER_ID);
    }

    @Test
    void listMapsAnUnknownOrForeignOrderTo404() throws Exception {
        authenticate();
        willThrow(new OrderNotFoundException(ORDER_ID))
                .given(paymentService).list(USER_ID, ORDER_ID);

        mockMvc.perform(get("/api/orders/7/payments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    // --- get -----------------------------------------------------------------

    @Test
    void getReturnsTheAttemptWithoutSecretsOrAProviderReference() throws Exception {
        authenticate();
        given(paymentService.get(USER_ID, ORDER_ID, PAYMENT_ID))
                .willReturn(response(PaymentStatus.PENDING, null));

        mockMvc.perform(get("/api/orders/7/payments/11"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value((int) PAYMENT_ID))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(179.98))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value("2026-11-09T12:00:00Z"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.publishableKey").doesNotExist())
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.providerRef").doesNotExist());

        then(paymentService).should().get(USER_ID, ORDER_ID, PAYMENT_ID);
    }

    @Test
    void getMapsAMissingOrForeignPaymentTo404() throws Exception {
        authenticate();
        willThrow(new PaymentNotFoundException(PAYMENT_ID))
                .given(paymentService).get(USER_ID, ORDER_ID, PAYMENT_ID);

        mockMvc.perform(get("/api/orders/7/payments/11"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Payment not found"));
    }

    @Test
    void getMapsAnUnknownOrForeignOrderTo404() throws Exception {
        authenticate();
        willThrow(new OrderNotFoundException(ORDER_ID))
                .given(paymentService).get(USER_ID, ORDER_ID, PAYMENT_ID);

        mockMvc.perform(get("/api/orders/7/payments/11"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    // --- cancel -------------------------------------------------------------

    @Test
    void cancelReturnsTheReleasedAttempt() throws Exception {
        authenticate();
        given(paymentService.cancel(USER_ID, ORDER_ID, PAYMENT_ID))
                .willReturn(response(PaymentStatus.FAILED, null));

        mockMvc.perform(post("/api/orders/7/payments/11/cancel"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value((int) PAYMENT_ID))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.providerRef").doesNotExist());

        then(paymentService).should().cancel(USER_ID, ORDER_ID, PAYMENT_ID);
    }

    @Test
    void cancelMapsAMissingOrForeignPaymentTo404() throws Exception {
        authenticate();
        willThrow(new PaymentNotFoundException(PAYMENT_ID))
                .given(paymentService).cancel(USER_ID, ORDER_ID, PAYMENT_ID);

        mockMvc.perform(post("/api/orders/7/payments/11/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Payment not found"));
    }

    @Test
    void cancelMapsAnAlreadySucceededPaymentTo409() throws Exception {
        authenticate();
        willThrow(new PaymentNotCancellableException(PaymentStatus.SUCCEEDED))
                .given(paymentService).cancel(USER_ID, ORDER_ID, PAYMENT_ID);

        mockMvc.perform(post("/api/orders/7/payments/11/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Payment cannot be cancelled"));
    }

    @Test
    void cancelMapsAProviderFailureTo502() throws Exception {
        authenticate();
        willThrow(new PaymentProviderException("stripe"))
                .given(paymentService).cancel(USER_ID, ORDER_ID, PAYMENT_ID);

        mockMvc.perform(post("/api/orders/7/payments/11/cancel"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Payment provider unavailable"));
    }

    // --- authentication -----------------------------------------------------

    @Test
    void aDeletedUserIs401OnEveryPaymentRoute() throws Exception {
        authenticate();
        willThrow(new UserNotFoundException(USER_ID))
                .given(paymentService).create(USER_ID, ORDER_ID);
        willThrow(new UserNotFoundException(USER_ID))
                .given(paymentService).list(USER_ID, ORDER_ID);
        willThrow(new UserNotFoundException(USER_ID))
                .given(paymentService).get(USER_ID, ORDER_ID, PAYMENT_ID);
        willThrow(new UserNotFoundException(USER_ID))
                .given(paymentService).cancel(USER_ID, ORDER_ID, PAYMENT_ID);

        mockMvc.perform(post("/api/orders/7/payments"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders/7/payments"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders/7/payments/11"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/orders/7/payments/11/cancel"))
                .andExpect(status().isUnauthorized());
    }

    // --- fixtures -----------------------------------------------------------

    private void authenticate() {
        Jwt token = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(String.valueOf(USER_ID))
                .claim("role", "CUSTOMER")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(token,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    private PaymentResponse response(PaymentStatus status, String failureCode) {
        return new PaymentResponse(
                PAYMENT_ID, status, AMOUNT, "USD", failureCode, CREATED);
    }

    private PaymentCreatedResponse createdResponse() {
        return new PaymentCreatedResponse(
                PAYMENT_ID, PaymentStatus.PENDING, AMOUNT, "USD", null, CREATED,
                "cs_test_secret", "pk_test_public");
    }
}
