package com.example.ecom.cart;

import com.example.ecom.auth.CurrentUserProvider;
import com.example.ecom.common.exception.ApiExceptionHandler;
import com.example.ecom.common.exception.CartItemNotFoundException;
import com.example.ecom.common.exception.InsufficientStockException;
import com.example.ecom.common.exception.ProductNotFoundException;
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
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice for the cart endpoints: controller, Jackson and the exception
 * advice are real, the service is a mock. Security filters are off, so the
 * verified token is put into the {@code SecurityContext} by hand, exactly as in
 * {@code MeEndpointTest}. Real authentication is proved by
 * {@code SecurityPerimeterTest}.
 * <p>
 * What is pinned here is the HTTP contract: every success returns the whole
 * cart and {@code Cache-Control: no-store}; the token's {@code sub} reaches the
 * service as a {@code long}; bad bodies become {@code 400} with an
 * {@code errors} map; unknown products and missing lines are {@code 404};
 * insufficient stock is {@code 409}; and no inventory field (sku, stock
 * quantity) or cart id leaks.
 */
@WebMvcTest(CartController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandler.class, CurrentUserProvider.class})
class CartControllerTest {

    private static final long USER_ID = 42L;
    private static final long PRODUCT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- reads -------------------------------------------------------------

    @Test
    void returnsTheWholeCartWithNoStore() throws Exception {
        authenticate();
        given(cartService.getCart(USER_ID)).willReturn(cart());

        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].product.id").value(1))
                .andExpect(jsonPath("$.items[0].product.slug").value("mechanical-keyboard"))
                .andExpect(jsonPath("$.items[0].product.name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.items[0].product.price").value(89.99))
                .andExpect(jsonPath("$.items[0].product.imageUrl").value("https://example.com/kb.png"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(179.98))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.totalQuantity").value(2))
                .andExpect(jsonPath("$.subtotal").value(179.98));

        then(cartService).should().getCart(USER_ID);
    }

    @Test
    void returnsAnEmptyCartWithoutWriting() throws Exception {
        authenticate();
        given(cartService.getCart(USER_ID))
                .willReturn(new CartResponse(List.of(), 0, new BigDecimal("0.00")));

        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalQuantity").value(0))
                .andExpect(jsonPath("$.subtotal").value(0.00));

        then(cartService).should().getCart(USER_ID);
        then(cartService).shouldHaveNoMoreInteractions();
    }

    @Test
    void doesNotExposeCartIdOrInventoryFields() throws Exception {
        authenticate();
        given(cartService.getCart(USER_ID)).willReturn(cart());

        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").doesNotExist())
                .andExpect(jsonPath("$.items[0].product.sku").doesNotExist())
                .andExpect(jsonPath("$.items[0].product.stockQuantity").doesNotExist())
                .andExpect(jsonPath("$.items[0].product.inStock").doesNotExist())
                .andExpect(jsonPath("$.items[0].product.category").doesNotExist())
                .andExpect(jsonPath("$.currency").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist());
    }

    // --- writes ------------------------------------------------------------

    @Test
    void addsAnItemForTheTokenSubjectAndReturnsTheCart() throws Exception {
        authenticate();
        given(cartService.addItem(USER_ID, PRODUCT_ID, 2)).willReturn(cart());

        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 1, "quantity": 2}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.subtotal").value(179.98));

        then(cartService).should().addItem(USER_ID, PRODUCT_ID, 2);
    }

    @Test
    void patchSetsAnAbsoluteQuantity() throws Exception {
        authenticate();
        given(cartService.changeQuantity(USER_ID, PRODUCT_ID, 5)).willReturn(cart());

        mockMvc.perform(patch("/api/cart/items/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 5}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.subtotal").value(179.98));

        then(cartService).should().changeQuantity(USER_ID, PRODUCT_ID, 5);
    }

    @Test
    void deletingAnItemReturnsTheCart() throws Exception {
        authenticate();
        given(cartService.removeItem(USER_ID, PRODUCT_ID)).willReturn(cart());

        mockMvc.perform(delete("/api/cart/items/1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1));

        then(cartService).should().removeItem(USER_ID, PRODUCT_ID);
    }

    // --- validation --------------------------------------------------------

    @Test
    void rejectsAnAddWithoutAProductId() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 2}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.productId").exists());

        then(cartService).shouldHaveNoInteractions();
    }

    @Test
    void rejectsAZeroQuantityOnAdd() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 1, "quantity": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").exists());

        then(cartService).shouldHaveNoInteractions();
    }

    @Test
    void rejectsANegativeQuantityOnAdd() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 1, "quantity": -1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").exists());

        then(cartService).shouldHaveNoInteractions();
    }

    @Test
    void rejectsAZeroQuantityOnUpdate() throws Exception {
        authenticate();

        mockMvc.perform(patch("/api/cart/items/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.quantity").exists());

        then(cartService).shouldHaveNoInteractions();
    }

    @Test
    void rejectsANonNumericProductIdInThePath() throws Exception {
        authenticate();

        mockMvc.perform(patch("/api/cart/items/not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/cart/items/not-a-number"));

        then(cartService).shouldHaveNoInteractions();
    }

    // --- policy errors -----------------------------------------------------

    @Test
    void mapsAnUnknownOrInactiveProductTo404() throws Exception {
        authenticate();
        willThrow(new ProductNotFoundException(PRODUCT_ID))
                .given(cartService).addItem(USER_ID, PRODUCT_ID, 1);

        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 1, "quantity": 1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Product not found"))
                .andExpect(jsonPath("$.detail").value("Product not found"))
                .andExpect(jsonPath("$.instance").value("/api/cart/items"));
    }

    @Test
    void mapsAMissingLineOnPatchTo404() throws Exception {
        authenticate();
        willThrow(new CartItemNotFoundException(PRODUCT_ID))
                .given(cartService).changeQuantity(USER_ID, PRODUCT_ID, 2);

        mockMvc.perform(patch("/api/cart/items/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 2}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cart item not found"))
                .andExpect(jsonPath("$.detail").value("Cart item not found"))
                .andExpect(jsonPath("$.instance").value("/api/cart/items/1"));
    }

    @Test
    void mapsInsufficientStockTo409WithoutLeakingTheStockNumber() throws Exception {
        authenticate();
        willThrow(new InsufficientStockException())
                .given(cartService).addItem(USER_ID, PRODUCT_ID, 99);

        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 1, "quantity": 99}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Not enough stock"))
                .andExpect(jsonPath("$.detail").value("Not enough stock available"));
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

    private CartResponse cart() {
        return new CartResponse(
                List.of(new CartItemResponse(
                        new CartProductResponse(
                                PRODUCT_ID,
                                "mechanical-keyboard",
                                "Mechanical Keyboard",
                                new BigDecimal("89.99"),
                                "https://example.com/kb.png"),
                        2,
                        new BigDecimal("179.98"),
                        true)),
                2,
                new BigDecimal("179.98"));
    }
}
