package com.example.ecom.cart;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.CategoryRepository;
import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.common.config.JpaAuditingConfig;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import com.example.ecom.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cart persistence against a real PostgreSQL container, so the Flyway DDL is
 * what is under test: the unique constraints, the cascade, and the entity graph.
 * <p>
 * The aggregate is loaded through {@code findByUserId} in every case that cares
 * about lines, because that is the only fetch path the service uses.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class CartRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    private User user;
    private Category category;

    @BeforeEach
    void setUp() {
        user = userRepository.saveAndFlush(
                new User("bob@example.com", "password-hash", "Bob", "Smith", Role.CUSTOMER));
        category = categoryRepository.saveAndFlush(
                new Category("Books", "books", "Paperbacks"));
    }

    @Test
    void findByUserIdIsEmptyWhenTheUserHasNoCart() {
        assertThat(cartRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void findsTheCartForTheUser() {
        Cart saved = cartRepository.saveAndFlush(new Cart(user));

        assertThat(cartRepository.findByUserId(user.getId()))
                .get()
                .extracting(Cart::getId)
                .isEqualTo(saved.getId());
    }

    @Test
    void oneCartPerUserIsEnforcedByTheDatabase() {
        cartRepository.saveAndFlush(new Cart(user));

        assertThatThrownBy(() -> cartRepository.saveAndFlush(new Cart(user)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneLinePerProductPerCartIsEnforcedByTheDatabase() {
        Cart cart = cartRepository.saveAndFlush(new Cart(user));
        Product product = product("SKU-1", "Keyboard", 10);

        entityManager.createNativeQuery(
                        "INSERT INTO cart_items (cart_id, product_id, quantity) "
                                + "VALUES (:cart, :product, 1)")
                .setParameter("cart", cart.getId())
                .setParameter("product", product.getId())
                .executeUpdate();

        // A second row for the same (cart, product) bypasses the entity, so the
        // unique constraint itself is what rejects it.
        assertThatThrownBy(() -> entityManager.createNativeQuery(
                        "INSERT INTO cart_items (cart_id, product_id, quantity) "
                                + "VALUES (:cart, :product, 2)")
                .setParameter("cart", cart.getId())
                .setParameter("product", product.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void deletingTheCartCascadesToItsItems() {
        Cart cart = cartRepository.saveAndFlush(new Cart(user));
        cart.addItem(product("SKU-1", "Keyboard", 10), 1);
        cart.addItem(product("SKU-2", "Mouse", 10), 1);
        cartRepository.saveAndFlush(cart);

        cartRepository.delete(cart);
        cartRepository.flush();

        long remaining = ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM cart_items WHERE cart_id = :cart")
                .setParameter("cart", cart.getId())
                .getSingleResult()).longValue();

        assertThat(remaining).isZero();
    }

    @Test
    void entityGraphLoadsItemsAndTheirProducts() {
        Cart cart = new Cart(user);
        cart.addItem(product("SKU-1", "Keyboard", 10), 2);
        cart.addItem(product("SKU-2", "Mouse", 10), 1);
        cartRepository.saveAndFlush(cart);
        entityManager.clear();

        Cart loaded = cartRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(loaded.getItems())).isTrue();
        assertThat(loaded.getItems()).hasSize(2);
        assertThat(loaded.getItems())
                .allSatisfy(item -> {
                    assertThat(Hibernate.isInitialized(item.getProduct())).isTrue();
                    assertThat(item.getProduct().getName()).isNotBlank();
                });
    }

    @Test
    void linesComeBackOldestFirstThenById() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", 10);
        Product mouse = product("SKU-2", "Mouse", 10);

        cart.addItem(mouse, 1);
        cartRepository.saveAndFlush(cart);
        cart.addItem(keyboard, 1);
        cartRepository.saveAndFlush(cart);
        entityManager.clear();

        Cart loaded = cartRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(loaded.getItems())
                .extracting(item -> item.getProduct().getSlug())
                .containsExactly("mouse", "keyboard");
    }

    private Product product(String sku, String name, int stock) {
        return productRepository.saveAndFlush(new Product(
                category, sku, name, name.toLowerCase(), null,
                new BigDecimal("19.99"), stock, null));
    }
}
