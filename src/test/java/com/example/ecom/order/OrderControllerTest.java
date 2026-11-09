package com.example.ecom.order;

import com.example.ecom.auth.CurrentUserProvider;
import com.example.ecom.common.exception.ApiExceptionHandler;
import com.example.ecom.common.exception.CartNotCheckoutableException;
import com.example.ecom.common.exception.EmptyCartException;
import com.example.ecom.common.exception.InvalidRequestException;
import com.example.ecom.common.exception.OrderNotCancellableException;
import com.example.ecom.common.exception.OrderNotFoundException;
import com.example.ecom.common.exception.UnavailableItem;
import com.example.ecom.common.exception.UnavailableReason;
import com.example.ecom.common.exception.UserNotFoundException;
import com.example.ecom.common.web.PageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * Web-layer slice for the order endpoints: controller, Jackson, validation and
 * the exception advice are real, the service is a mock. Security filters are
 * off, so the verified token is put into the {@code SecurityContext} by hand,
 * exactly as in {@code CartControllerTest}.
 * <p>
 * What is pinned here is the HTTP contract: checkout is {@code 201} with a
 * {@code Location} header; every response is {@code Cache-Control: no-store};
 * the token's {@code sub} reaches the service as a {@code long}; bad shipping
 * bodies become {@code 400} with dotted {@code errors} keys; an empty or
 * unavailable cart is {@code 409}; an unknown or foreign order is {@code 404};
 * and no inventory field leaks.
 */
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandler.class, CurrentUserProvider.class})
class OrderControllerTest {

    private static final long USER_ID = 42L;
    private static final long ORDER_ID = 7L;
    private static final Instant CREATED = Instant.parse("2026-11-08T12:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-11-08T12:05:00Z");

    private static final String VALID_SHIPPING = """
            {"shipping":{"name":"Bob Smith","line1":"1 Main St","line2":"Apt 2",\
            "city":"Springfield","postalCode":"12345","country":"us"}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- checkout ----------------------------------------------------------

    @Test
    void checkoutReturns201WithLocationAndNoStore() throws Exception {
        authenticate();
        given(orderService.checkout(eq(USER_ID), any(ShippingAddress.class)))
                .willReturn(orderResponse(OrderStatus.PENDING));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPPING))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/7"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.subtotal").value(179.98))
                .andExpect(jsonPath("$.total").value(179.98))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.totalQuantity").value(2))
                .andExpect(jsonPath("$.shipping.country").value("US"))
                .andExpect(jsonPath("$.items[0].productId").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(89.99))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(179.98))
                .andExpect(jsonPath("$.createdAt").value("2026-11-08T12:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-08T12:05:00Z"));
    }

    @Test
    void checkoutNormalizesTheCountryBeforeCallingTheService() throws Exception {
        authenticate();
        given(orderService.checkout(eq(USER_ID), any(ShippingAddress.class)))
                .willReturn(orderResponse(OrderStatus.PENDING));
        ArgumentCaptor<ShippingAddress> address = ArgumentCaptor.forClass(ShippingAddress.class);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPPING))
                .andExpect(status().isCreated());

        then(orderService).should().checkout(eq(USER_ID), address.capture());
        assertThat(address.getValue().getCountry()).isEqualTo("US");
        assertThat(address.getValue().getLine2()).isEqualTo("Apt 2");
    }

    @Test
    void checkoutRejectsAMissingShippingObject() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.shipping").exists());

        then(orderService).shouldHaveNoInteractions();
    }

    @Test
    void checkoutRejectsAMissingName() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shipping":{"line1":"1 Main St","city":"Springfield",\
                                "postalCode":"12345","country":"US"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors['shipping.name']").exists());

        then(orderService).shouldHaveNoInteractions();
    }

    @Test
    void checkoutRejectsACountryThatIsNotTwoLetters() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shipping":{"name":"Bob Smith","line1":"1 Main St",\
                                "city":"Springfield","postalCode":"12345","country":"USA"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['shipping.country']").exists());

        then(orderService).shouldHaveNoInteractions();
    }

    @Test
    void checkoutMapsAnEmptyCartTo409() throws Exception {
        authenticate();
        willThrow(new EmptyCartException())
                .given(orderService).checkout(eq(USER_ID), any(ShippingAddress.class));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPPING))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Cart is empty"))
                .andExpect(jsonPath("$.detail").value("Cart is empty"));
    }

    @Test
    void checkoutMapsUnavailableLinesTo409WithoutLeakingStock() throws Exception {
        authenticate();
        willThrow(new CartNotCheckoutableException(List.of(
                new UnavailableItem(1L, "mechanical-keyboard", UnavailableReason.INACTIVE))))
                .given(orderService).checkout(eq(USER_ID), any(ShippingAddress.class));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPPING))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cart cannot be checked out"))
                .andExpect(jsonPath("$.detail").value("One or more items are unavailable"))
                .andExpect(jsonPath("$.unavailableItems[0].productId").value(1))
                .andExpect(jsonPath("$.unavailableItems[0].slug").value("mechanical-keyboard"))
                .andExpect(jsonPath("$.unavailableItems[0].reason").value("INACTIVE"))
                .andExpect(jsonPath("$.unavailableItems[0].stockQuantity").doesNotExist());
    }

    @Test
    void checkoutMapsADeletedUserTo401() throws Exception {
        authenticate();
        willThrow(new UserNotFoundException(USER_ID))
                .given(orderService).checkout(eq(USER_ID), any(ShippingAddress.class));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPPING))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    // --- list --------------------------------------------------------------

    @Test
    void listReturnsThePageEnvelopeOfSummaries() throws Exception {
        authenticate();
        given(orderService.listOrders(USER_ID, 0, 20))
                .willReturn(new PageResponse<>(List.of(summaryResponse()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].id").value(7))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].subtotal").value(179.98))
                .andExpect(jsonPath("$.items[0].total").value(179.98))
                .andExpect(jsonPath("$.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.items[0].totalQuantity").value(2))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-08T12:00:00Z"))
                .andExpect(jsonPath("$.items[0].shipping").doesNotExist())
                .andExpect(jsonPath("$.items[0].items").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listPassesThePagingParametersThrough() throws Exception {
        authenticate();
        given(orderService.listOrders(USER_ID, 2, 5))
                .willReturn(new PageResponse<>(List.of(), 2, 5, 0, 0));

        mockMvc.perform(get("/api/orders").param("page", "2").param("size", "5"))
                .andExpect(status().isOk());

        then(orderService).should().listOrders(USER_ID, 2, 5);
    }

    @Test
    void listMapsAnInvalidPageTo400() throws Exception {
        authenticate();
        willThrow(new InvalidRequestException("page must be zero or greater"))
                .given(orderService).listOrders(USER_ID, -1, 20);

        mockMvc.perform(get("/api/orders").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    // --- detail ------------------------------------------------------------

    @Test
    void detailReturnsTheFullOrder() throws Exception {
        authenticate();
        given(orderService.getOrder(USER_ID, ORDER_ID))
                .willReturn(orderResponse(OrderStatus.PENDING));

        mockMvc.perform(get("/api/orders/7"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.shipping.name").value("Bob Smith"))
                .andExpect(jsonPath("$.shipping.country").value("US"))
                .andExpect(jsonPath("$.items[0].productName").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.items[0].lineTotal").value(179.98));
    }

    @Test
    void detailMapsAMissingOrForeignOrderTo404() throws Exception {
        authenticate();
        willThrow(new OrderNotFoundException(ORDER_ID))
                .given(orderService).getOrder(USER_ID, ORDER_ID);

        mockMvc.perform(get("/api/orders/7"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void detailDoesNotExposeInventoryOrOwnerFields() throws Exception {
        authenticate();
        given(orderService.getOrder(USER_ID, ORDER_ID))
                .willReturn(orderResponse(OrderStatus.PENDING));

        mockMvc.perform(get("/api/orders/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.items[0].sku").doesNotExist())
                .andExpect(jsonPath("$.items[0].stockQuantity").doesNotExist())
                .andExpect(jsonPath("$.items[0].productSlug").doesNotExist())
                .andExpect(jsonPath("$.items[0].imageUrl").doesNotExist());
    }

    // --- cancellation ------------------------------------------------------

    @Test
    void cancelReturnsTheCancelledOrder() throws Exception {
        authenticate();
        given(orderService.cancel(USER_ID, ORDER_ID))
                .willReturn(orderResponse(OrderStatus.CANCELLED));

        mockMvc.perform(post("/api/orders/7/cancel"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelMapsANonCancellableOrderTo409() throws Exception {
        authenticate();
        willThrow(new OrderNotCancellableException("PAID"))
                .given(orderService).cancel(USER_ID, ORDER_ID);

        mockMvc.perform(post("/api/orders/7/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order cannot be cancelled"))
                .andExpect(jsonPath("$.detail").value("Order cannot be cancelled"));
    }

    @Test
    void cancelMapsAMissingOrForeignOrderTo404() throws Exception {
        authenticate();
        willThrow(new OrderNotFoundException(ORDER_ID))
                .given(orderService).cancel(USER_ID, ORDER_ID);

        mockMvc.perform(post("/api/orders/7/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void aDeletedUserIs401OnEveryOrderRoute() throws Exception {
        authenticate();
        willThrow(new UserNotFoundException(USER_ID))
                .given(orderService).listOrders(USER_ID, 0, 20);
        willThrow(new UserNotFoundException(USER_ID))
                .given(orderService).getOrder(USER_ID, ORDER_ID);
        willThrow(new UserNotFoundException(USER_ID))
                .given(orderService).cancel(USER_ID, ORDER_ID);

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders/7"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/orders/7/cancel"))
                .andExpect(status().isUnauthorized());
    }

    // --- fixtures ----------------------------------------------------------

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

    private OrderResponse orderResponse(OrderStatus status) {
        return new OrderResponse(
                ORDER_ID,
                status,
                new BigDecimal("179.98"),
                new BigDecimal("179.98"),
                "USD",
                new ShippingAddressResponse(
                        "Bob Smith", "1 Main St", "Apt 2",
                        "Springfield", "12345", "US"),
                List.of(new OrderItemResponse(
                        1L, "Mechanical Keyboard",
                        new BigDecimal("89.99"), 2, new BigDecimal("179.98"))),
                2,
                CREATED,
                UPDATED);
    }

    private OrderSummaryResponse summaryResponse() {
        return new OrderSummaryResponse(
                ORDER_ID, OrderStatus.PENDING,
                new BigDecimal("179.98"), new BigDecimal("179.98"),
                "USD", 2, CREATED);
    }
}
