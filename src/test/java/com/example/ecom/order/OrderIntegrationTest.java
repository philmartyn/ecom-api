package com.example.ecom.order;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order slice end to end: real filter chain, real tokens, real controller,
 * service and PostgreSQL. Users are registered and logged in over HTTP, so the
 * token's {@code sub} is genuine rather than hand-built.
 * <p>
 * {@code @Transactional} rolls everything back after each test, keeping the
 * shared container clean. Because MockMvc runs on the test thread, the service
 * joins this transaction, which is why a cart written by one request is visible
 * to the next.
 * <p>
 * The cases that are easy to get wrong get a test: the order is created without
 * moving stock, the cart is emptied but its row survives, the line snapshot
 * survives a reprice, unavailable lines reject the whole order, another user's
 * order is a 404, cancellation is idempotent and does not restore the cart, and
 * a deleted user's token is 401 on every order route.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderIntegrationTest extends AbstractIntegrationTest {

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

    // --- checkout ----------------------------------------------------------

    @Test
    void checkoutCreatesAPendingOrderAndEmptiesTheCart() throws Exception {
        add(aliceToken, keyboard.getId(), 2);
        add(aliceToken, mouse.getId(), 1);

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipping("us")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(response, "$.id")).longValue();

        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/orders/" + orderId);
        assertThat((String) JsonPath.read(response, "$.status")).isEqualTo("PENDING");
        assertThat(((Number) JsonPath.read(response, "$.subtotal")).doubleValue())
                .isEqualTo(219.97); // 2 * 89.99 + 39.99
        assertThat(((Number) JsonPath.read(response, "$.total")).doubleValue())
                .isEqualTo(219.97);
        assertThat((String) JsonPath.read(response, "$.currency")).isEqualTo("USD");
        assertThat((Integer) JsonPath.read(response, "$.totalQuantity")).isEqualTo(3);
        assertThat((String) JsonPath.read(response, "$.shipping.country")).isEqualTo("US");
        assertThat((Integer) JsonPath.read(response, "$.items.length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(response, "$.items[0].productName"))
                .isEqualTo("Mechanical Keyboard");
        assertThat(((Number) JsonPath.read(response, "$.items[0].unitPrice")).doubleValue())
                .isEqualTo(89.99);
        assertThat((Integer) JsonPath.read(response, "$.items[0].quantity")).isEqualTo(2);
        assertThat(((Number) JsonPath.read(response, "$.items[0].lineTotal")).doubleValue())
                .isEqualTo(179.98);

        assertThat(orderRowsFor(aliceId)).isEqualTo(1);
        assertThat(orderItemsFor(orderId)).isEqualTo(2);
        assertThat(cartItemsFor(aliceId)).isZero();
        assertThat(cartRowsFor(aliceId)).isEqualTo(1);
        assertThat(stockOf(keyboard.getId())).isEqualTo(10);
        assertThat(stockOf(mouse.getId())).isEqualTo(10);
    }

    @Test
    void checkoutSnapshotsTheNameAndPriceAtCheckout() throws Exception {
        add(aliceToken, keyboard.getId(), 2);
        long orderId = checkout(aliceToken, "US");

        setProductNameAndPrice(keyboard.getId(), "Renamed Keyboard", "199.99");
        entityManager.clear();

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(89.99))
                .andExpect(jsonPath("$.items[0].lineTotal").value(179.98));
    }

    @Test
    void checkoutDoesNotDecrementStock() throws Exception {
        add(aliceToken, keyboard.getId(), 2);

        checkout(aliceToken, "US");

        assertThat(stockOf(keyboard.getId())).isEqualTo(10);
    }

    @Test
    void checkoutWithNoCartIs409AndCreatesNoOrder() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipping("US")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Cart is empty"))
                .andExpect(jsonPath("$.detail").value("Cart is empty"));

        assertThat(orderRowsFor(aliceId)).isZero();
    }

    @Test
    void checkoutWithAnEmptyExistingCartIs409() throws Exception {
        add(aliceToken, keyboard.getId(), 1);
        mockMvc.perform(delete("/api/cart/items/" + keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipping("US")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cart is empty"));

        assertThat(orderRowsFor(aliceId)).isZero();
        assertThat(cartRowsFor(aliceId)).isEqualTo(1);
    }

    @Test
    void checkoutWithAnInactiveLineIs409AndNamesIt() throws Exception {
        add(aliceToken, keyboard.getId(), 2);
        keyboard.deactivate();
        productRepository.saveAndFlush(keyboard);
        entityManager.clear();

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipping("US")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cart cannot be checked out"))
                .andExpect(jsonPath("$.detail").value("One or more items are unavailable"))
                .andExpect(jsonPath("$.unavailableItems[0].productId")
                        .value(keyboard.getId().intValue()))
                .andExpect(jsonPath("$.unavailableItems[0].slug").value("mechanical-keyboard"))
                .andExpect(jsonPath("$.unavailableItems[0].reason").value("INACTIVE"))
                .andExpect(jsonPath("$.unavailableItems[0].stockQuantity").doesNotExist());

        assertThat(orderRowsFor(aliceId)).isZero();
        assertThat(cartItemsFor(aliceId)).isEqualTo(1);
        assertThat(stockOf(keyboard.getId())).isEqualTo(10);
    }

    @Test
    void checkoutWithInsufficientStockIs409() throws Exception {
        add(aliceToken, keyboard.getId(), 2);
        entityManager.clear();
        setStock(keyboard.getId(), 1);

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipping("US")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableItems[0].reason").value("INSUFFICIENT_STOCK"));

        assertThat(orderRowsFor(aliceId)).isZero();
        assertThat(stockOf(keyboard.getId())).isEqualTo(1);
    }

    @Test
    void inactiveWinsOverInsufficientStock() throws Exception {
        add(aliceToken, keyboard.getId(), 2);
        keyboard.deactivate();
        productRepository.saveAndFlush(keyboard);
        // The native update runs after the entity flush, so it is not overwritten
        // by the managed Product still holding stock 10.
        setStock(keyboard.getId(), 0);
        entityManager.clear();

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipping("US")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableItems[0].reason").value("INACTIVE"));
    }

    @Test
    void checkoutRejectsAnInvalidShippingAddress() throws Exception {
        add(aliceToken, keyboard.getId(), 1);

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shipping":{"line1":"1 Main St","city":"Springfield",\
                                "postalCode":"12345","country":"USA"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors['shipping.name']").exists())
                .andExpect(jsonPath("$.errors['shipping.country']").exists());

        assertThat(orderRowsFor(aliceId)).isZero();
        assertThat(cartItemsFor(aliceId)).isEqualTo(1);
    }

    // --- list and detail ---------------------------------------------------

    @Test
    void listReturnsOnlyTheCallersOrdersNewestFirst() throws Exception {
        add(aliceToken, keyboard.getId(), 1);
        long first = checkout(aliceToken, "US");
        add(aliceToken, mouse.getId(), 2);
        long second = checkout(aliceToken, "US");

        long bobId = register("bob@example.com");
        String bobToken = login("bob@example.com");
        add(bobToken, keyboard.getId(), 1);
        checkout(bobToken, "US");

        mockMvc.perform(get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value((int) second))
                .andExpect(jsonPath("$.items[1].id").value((int) first))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].totalQuantity").value(2))
                .andExpect(jsonPath("$.items[0].shipping").doesNotExist())
                .andExpect(jsonPath("$.items[0].items").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void detailReturnsTheFullOrder() throws Exception {
        add(aliceToken, keyboard.getId(), 2);
        long orderId = checkout(aliceToken, "US");

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value((int) orderId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.subtotal").value(179.98))
                .andExpect(jsonPath("$.total").value(179.98))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.totalQuantity").value(2))
                .andExpect(jsonPath("$.shipping.name").value("Bob Smith"))
                .andExpect(jsonPath("$.shipping.line2").value("Apt 2"))
                .andExpect(jsonPath("$.shipping.country").value("US"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId")
                        .value(keyboard.getId().intValue()))
                .andExpect(jsonPath("$.items[0].productName").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(89.99))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(179.98))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.items[0].sku").doesNotExist())
                .andExpect(jsonPath("$.items[0].stockQuantity").doesNotExist())
                .andExpect(jsonPath("$.items[0].productSlug").doesNotExist())
                .andExpect(jsonPath("$.items[0].imageUrl").doesNotExist());
    }

    @Test
    void detailForAnotherUsersOrderIs404() throws Exception {
        add(aliceToken, keyboard.getId(), 1);
        long orderId = checkout(aliceToken, "US");
        register("bob@example.com");
        String bobToken = login("bob@example.com");

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void unknownOrderIs404() throws Exception {
        mockMvc.perform(get("/api/orders/999999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    // --- cancellation ------------------------------------------------------

    @Test
    void cancelMovesPendingToCancelled() throws Exception {
        add(aliceToken, keyboard.getId(), 1);
        long orderId = checkout(aliceToken, "US");

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value((int) orderId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    void cancelIsIdempotent() throws Exception {
        add(aliceToken, keyboard.getId(), 1);
        long orderId = checkout(aliceToken, "US");

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelDoesNotRestoreTheCart() throws Exception {
        add(aliceToken, keyboard.getId(), 2);
        long orderId = checkout(aliceToken, "US");

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalQuantity").value(0));

        assertThat(cartRowsFor(aliceId)).isEqualTo(1);
        assertThat(cartItemsFor(aliceId)).isZero();
    }

    @Test
    void cancelAnotherUsersOrderIs404() throws Exception {
        add(aliceToken, keyboard.getId(), 1);
        long orderId = checkout(aliceToken, "US");
        register("bob@example.com");
        String bobToken = login("bob@example.com");

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void cancelAnOrderThatHasMovedOnIs409() throws Exception {
        add(aliceToken, keyboard.getId(), 1);
        long orderId = checkout(aliceToken, "US");
        setOrderStatus(orderId, "PAID");
        entityManager.clear();

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order cannot be cancelled"));
    }

    @Test
    void aTokenForADeletedUserIs401OnEveryOrderRoute() throws Exception {
        long bobId = register("bob@example.com");
        String bobToken = login("bob@example.com");
        userRepository.deleteById(bobId);
        entityManager.flush();

        mockMvc.perform(get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        mockMvc.perform(get("/api/orders/999999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/orders/999999/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipping("US")))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers -----------------------------------------------------------

    private long checkout(String token, String country) throws Exception {
        String response = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipping(country)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private String add(String token, long productId, int quantity) throws Exception {
        return mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": %d, "quantity": %d}
                                """.formatted(productId, quantity)))
                .andExpect(status().isOk())
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

    private static String shipping(String country) {
        return """
                {"shipping":{"name":"Bob Smith","line1":"1 Main St","line2":"Apt 2",\
                "city":"Springfield","postalCode":"12345","country":"%s"}}
                """.formatted(country);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
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

    private long orderRowsFor(long userId) {
        return ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM orders WHERE user_id = :user")
                .setParameter("user", userId)
                .getSingleResult()).longValue();
    }

    private long orderItemsFor(long orderId) {
        return ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM order_items WHERE order_id = :order")
                .setParameter("order", orderId)
                .getSingleResult()).longValue();
    }

    private int stockOf(long productId) {
        return ((Number) entityManager
                .createNativeQuery("SELECT stock_quantity FROM products WHERE id = :id")
                .setParameter("id", productId)
                .getSingleResult()).intValue();
    }

    private String orderStatus(long orderId) {
        return (String) entityManager
                .createNativeQuery("SELECT status FROM orders WHERE id = :id")
                .setParameter("id", orderId)
                .getSingleResult();
    }

    private void setStock(long productId, int stock) {
        entityManager.createNativeQuery(
                        "UPDATE products SET stock_quantity = :stock WHERE id = :id")
                .setParameter("stock", stock)
                .setParameter("id", productId)
                .executeUpdate();
    }

    private void setProductNameAndPrice(long productId, String name, String price) {
        entityManager.createNativeQuery(
                        "UPDATE products SET name = :name, price = :price WHERE id = :id")
                .setParameter("name", name)
                .setParameter("price", new BigDecimal(price))
                .setParameter("id", productId)
                .executeUpdate();
    }

    private void setOrderStatus(long orderId, String status) {
        entityManager.createNativeQuery(
                        "UPDATE orders SET status = :status WHERE id = :id")
                .setParameter("status", status)
                .setParameter("id", orderId)
                .executeUpdate();
    }
}
