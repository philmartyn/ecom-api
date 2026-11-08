package com.example.ecom.cart;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.CategoryRepository;
import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cart slice end to end: real filter chain, real tokens, real controller,
 * service and PostgreSQL. Users are registered and logged in over HTTP, so the
 * token's {@code sub} is genuine rather than hand-built.
 * <p>
 * {@code @Transactional} rolls everything back after each test, keeping the
 * shared container clean. Because MockMvc runs on the test thread, the service
 * joins this transaction, which is why a cart written by one request is visible
 * to the next.
 * <p>
 * The cases that are easy to get wrong get a test: lazy creation and the
 * read-only empty GET, accumulate vs absolute quantity, idempotent delete,
 * isolation between users, inactive products, stock drops, current prices, and
 * ordering.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CartIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private Product keyboard;
    private Product mouse;
    private long aliceId;
    private String aliceToken;

    @BeforeEach
    void seed() throws Exception {
        Category keyboards = categoryRepository.saveAndFlush(
                new Category("Keyboards", "keyboards", "Input devices"));
        Category mice = categoryRepository.saveAndFlush(
                new Category("Mice", "mice", "Pointing devices"));

        keyboard = productRepository.saveAndFlush(new Product(
                keyboards, "SKU-KB-1", "Mechanical Keyboard", "mechanical-keyboard",
                "Clicky keys", new BigDecimal("89.99"), 10, "https://example.com/kb.png"));
        mouse = productRepository.saveAndFlush(new Product(
                mice, "SKU-MS-1", "Wireless Mouse", "wireless-mouse",
                "Silent clicks", new BigDecimal("39.99"), 10, "https://example.com/ms.png"));

        aliceId = register("alice@example.com");
        aliceToken = login("alice@example.com");
    }

    // --- lazy creation and reads ------------------------------------------

    @Test
    void getBeforeAnyAddReturnsA200EmptyCartAndDoesNotWrite() throws Exception {
        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalQuantity").value(0))
                .andExpect(jsonPath("$.subtotal").value(0.00));

        assertThat(cartRowsFor(aliceId)).isZero();
    }

    @Test
    void addingAnItemCreatesTheCartLazily() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        assertThat(cartRowsFor(aliceId)).isEqualTo(1);
        assertThat(cartItemsFor(aliceId)).isEqualTo(1);
    }

    @Test
    void emptyingTheCartKeepsTheEmptyCartRow() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        mockMvc.perform(delete("/api/cart/items/" + keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.subtotal").value(0.00));

        assertThat(cartRowsFor(aliceId)).isEqualTo(1);
    }

    // --- add and update semantics -----------------------------------------

    @Test
    void addingTheSameProductTwiceAccumulates() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        String response = add(aliceToken, keyboard.getId(), 3);

        assertThat((Integer) JsonPath.read(response, "$.items.length()")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(response, "$.items[0].quantity")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(response, "$.totalQuantity")).isEqualTo(5);
        assertThat(((Number) JsonPath.read(response, "$.subtotal")).doubleValue())
                .isEqualTo(449.95); // 5 * 89.99
    }

    @Test
    void patchSetsAnAbsoluteQuantityRatherThanADelta() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        mockMvc.perform(patch("/api/cart/items/" + keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 4}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(4))
                .andExpect(jsonPath("$.totalQuantity").value(4))
                .andExpect(jsonPath("$.items[0].lineTotal").value(359.96));
    }

    @Test
    void patchWithZeroIsAValidationErrorNotADelete() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        mockMvc.perform(patch("/api/cart/items/" + keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors.quantity").exists());

        assertThat(cartItemsFor(aliceId)).isEqualTo(1);
    }

    @Test
    void patchForAProductNotInTheCartIs404() throws Exception {
        mockMvc.perform(patch("/api/cart/items/" + keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cart item not found"))
                .andExpect(jsonPath("$.detail").value("Cart item not found"));
    }

    @Test
    void deletingALineIsIdempotent() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        mockMvc.perform(delete("/api/cart/items/" + keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        mockMvc.perform(delete("/api/cart/items/" + keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalQuantity").value(0));
    }

    // --- isolation ---------------------------------------------------------

    @Test
    void oneUsersCartIsInvisibleToAnother() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        register("bob@example.com");
        String bobToken = login("bob@example.com");

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalQuantity").value(0));
    }

    // --- catalog policy ----------------------------------------------------

    @Test
    void addingAnInactiveProductIs404() throws Exception {
        keyboard.deactivate();
        productRepository.saveAndFlush(keyboard);

        mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": %d, "quantity": 1}
                                """.formatted(keyboard.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"));
    }

    @Test
    void addingAnUnknownProductIs404() throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 999999, "quantity": 1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"));
    }

    @Test
    void addingMoreThanStockIs409WithoutLeakingTheNumber() throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": %d, "quantity": 11}
                                """.formatted(keyboard.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Not enough stock"))
                // The detail is deliberately generic: the exact stock number is
                // inventory information the Catalog does not expose either.
                .andExpect(jsonPath("$.detail").value("Not enough stock available"));
    }

    @Test
    void updatingBeyondStockIs409() throws Exception {
        add(aliceToken, keyboard.getId(), 1);

        mockMvc.perform(patch("/api/cart/items/" + keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 11}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Not enough stock"));
    }

    @Test
    void accumulatingBeyondStockIs409AndLeavesTheLineUnchanged() throws Exception {
        add(aliceToken, keyboard.getId(), 8);

        mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": %d, "quantity": 3}
                                """.formatted(keyboard.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Not enough stock"));

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(8));
    }

    @Test
    void patchingAnUnknownProductIs404() throws Exception {
        mockMvc.perform(patch("/api/cart/items/999999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"));
    }

    @Test
    void patchingAnInactiveLineIs404EvenThoughItStaysVisible() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        keyboard.deactivate();
        productRepository.saveAndFlush(keyboard);

        mockMvc.perform(patch("/api/cart/items/" + keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"));

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].available").value(false));
    }

    @Test
    void aDeactivatedProductStaysVisibleButUnavailable() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        keyboard.deactivate();
        productRepository.saveAndFlush(keyboard);

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].product.slug").value("mechanical-keyboard"))
                .andExpect(jsonPath("$.items[0].available").value(false));
    }

    @Test
    void aStockDropBelowTheLineQuantityMakesItUnavailable() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        setStock(keyboard.getId(), 1);
        entityManager.clear();

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].available").value(false));
    }

    // --- pricing and ordering ---------------------------------------------

    @Test
    void theCartUsesTheCurrentPriceNotASnapshot() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        setPrice(keyboard.getId(), "99.99");
        entityManager.clear();

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].product.price").value(99.99))
                .andExpect(jsonPath("$.items[0].lineTotal").value(199.98))
                .andExpect(jsonPath("$.subtotal").value(199.98));
    }

    @Test
    void linesAreOrderedOldestFirst() throws Exception {
        add(aliceToken, mouse.getId(), 1);
        add(aliceToken, keyboard.getId(), 1);

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].product.slug").value("wireless-mouse"))
                .andExpect(jsonPath("$.items[1].product.slug").value("mechanical-keyboard"));
    }

    @Test
    void aTokenForADeletedUserIs401() throws Exception {
        userRepository.deleteById(aliceId);
        entityManager.flush();

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    // --- helpers -----------------------------------------------------------

    private String add(String token, long productId, int quantity) throws Exception {
        return mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": %d, "quantity": %d}
                                """.formatted(productId, quantity)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
    }

    private long register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"%s","firstName":"Alice","lastName":"Smith"}
                """.formatted(email, PASSWORD);

        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private String login(String email) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(response, "$.accessToken");
    }

    private long cartRowsFor(long userId) {
        return ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM carts WHERE user_id = :user")
                .setParameter("user", userId)
                .getSingleResult()).longValue();
    }

    private long cartItemsFor(long userId) {
        return ((Number) entityManager
                .createNativeQuery("""
                        SELECT count(*) FROM cart_items
                        WHERE cart_id = (SELECT id FROM carts WHERE user_id = :user)
                        """)
                .setParameter("user", userId)
                .getSingleResult()).longValue();
    }

    private void setStock(long productId, int stock) {
        entityManager.createNativeQuery(
                        "UPDATE products SET stock_quantity = :stock WHERE id = :id")
                .setParameter("stock", stock)
                .setParameter("id", productId)
                .executeUpdate();
    }

    private void setPrice(long productId, String price) {
        entityManager.createNativeQuery(
                        "UPDATE products SET price = :price WHERE id = :id")
                .setParameter("price", new BigDecimal(price))
                .setParameter("id", productId)
                .executeUpdate();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
