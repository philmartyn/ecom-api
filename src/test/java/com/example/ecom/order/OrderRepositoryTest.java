package com.example.ecom.order;

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
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order persistence against a real PostgreSQL container, so the Flyway DDL is
 * what is under test: the snapshots, the cascade, the foreign keys and the
 * entity graph.
 * <p>
 * The aggregate is loaded through {@code findByIdAndUserId} in every case that
 * cares about lines, because that is the detail fetch path the service uses.
 * The list path deliberately does not fetch items in the same query: a
 * collection fetch plus paging would make Hibernate paginate in memory, so the
 * summary relies on lazy loading inside the service's transaction instead.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class OrderRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    private User owner;
    private User other;
    private Category category;
    private Product keyboard;
    private Product mouse;

    private final ShippingAddress shipping = new ShippingAddress(
            "Bob Smith", "1 Main St", "Apt 2", "Springfield", "12345", "us");

    @BeforeEach
    void setUp() {
        owner = userRepository.saveAndFlush(new User(
                "order-owner@example.com", "password-hash", "Bob", "Smith", Role.CUSTOMER));
        other = userRepository.saveAndFlush(new User(
                "order-other@example.com", "password-hash", "Alice", "Jones", Role.CUSTOMER));
        category = categoryRepository.saveAndFlush(
                new Category("Books", "books", "Paperbacks"));
        keyboard = product("SKU-KB-1", "Mechanical Keyboard", "mechanical-keyboard", "89.99");
        mouse = product("SKU-MS-1", "Wireless Mouse", "wireless-mouse", "39.99");
    }

    @Test
    void savesAndReloadsTheOrderWithItsItems() {
        Order order = new Order(owner, shipping);
        order.addItem(keyboard, 2);
        order.addItem(mouse, 1);
        orderRepository.saveAndFlush(order);
        entityManager.clear();

        Order loaded = orderRepository
                .findByIdAndUserId(order.getId(), owner.getId())
                .orElseThrow();

        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(loaded.getCurrency()).isEqualTo("USD");
        assertThat(loaded.getSubtotal()).isEqualByComparingTo("219.97");
        assertThat(loaded.getTotal()).isEqualByComparingTo("219.97");
        assertThat(loaded.getTotalQuantity()).isEqualTo(3);
        assertThat(loaded.getShippingAddress().getCountry()).isEqualTo("US");
        assertThat(loaded.getItems()).hasSize(2);
        assertThat(loaded.getItems())
                .extracting(OrderItem::getProductName)
                .containsExactly("Mechanical Keyboard", "Wireless Mouse");
        assertThat(loaded.getItems())
                .extracting(OrderItem::getUnitPrice)
                .allSatisfy(price -> assertThat(price.scale()).isEqualTo(2));
    }

    @Test
    void detailLookupLoadsItemsEagerly() {
        Order order = new Order(owner, shipping);
        order.addItem(keyboard, 2);
        orderRepository.saveAndFlush(order);
        entityManager.clear();

        Order loaded = orderRepository
                .findByIdAndUserId(order.getId(), owner.getId())
                .orElseThrow();

        assertThat(Hibernate.isInitialized(loaded.getItems())).isTrue();
    }

    @Test
    void detailLookupIsScopedToTheOwner() {
        Order order = new Order(owner, shipping);
        order.addItem(keyboard, 1);
        orderRepository.saveAndFlush(order);

        assertThat(orderRepository.findByIdAndUserId(order.getId(), other.getId()))
                .isEmpty();
    }

    @Test
    void listIsNewestFirstAndScopedToTheUser() {
        Order first = orderRepository.saveAndFlush(new Order(owner, shipping));
        Order second = orderRepository.saveAndFlush(new Order(owner, shipping));
        orderRepository.saveAndFlush(new Order(other, shipping));

        Page<Order> page = orderRepository.findByUserId(owner.getId(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))));

        assertThat(page.getContent())
                .extracting(Order::getId)
                .containsExactly(second.getId(), first.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void deletingAnOrderCascadesToItsItems() {
        Order order = new Order(owner, shipping);
        order.addItem(keyboard, 1);
        order.addItem(mouse, 1);
        orderRepository.saveAndFlush(order);

        orderRepository.delete(order);
        orderRepository.flush();

        long remaining = ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM order_items WHERE order_id = :order")
                .setParameter("order", order.getId())
                .getSingleResult()).longValue();

        assertThat(remaining).isZero();
    }

    @Test
    void orderItemsKeepTheirSnapshotWhenTheProductChanges() {
        Order order = new Order(owner, shipping);
        order.addItem(keyboard, 2);
        orderRepository.saveAndFlush(order);
        entityManager.clear();

        entityManager.createNativeQuery(
                        "UPDATE products SET name = :name, price = :price WHERE id = :id")
                .setParameter("name", "Renamed Keyboard")
                .setParameter("price", new BigDecimal("199.99"))
                .setParameter("id", keyboard.getId())
                .executeUpdate();
        entityManager.clear();

        Order loaded = orderRepository
                .findByIdAndUserId(order.getId(), owner.getId())
                .orElseThrow();
        OrderItem item = loaded.getItems().getFirst();

        assertThat(item.getProductName()).isEqualTo("Mechanical Keyboard");
        assertThat(item.getUnitPrice()).isEqualByComparingTo("89.99");
        assertThat(item.lineTotal()).isEqualByComparingTo("179.98");
    }

    @Test
    void theDatabaseRefusesToDeleteAUserWhoHasOrders() {
        Order order = new Order(owner, shipping);
        order.addItem(keyboard, 1);
        orderRepository.saveAndFlush(order);
        entityManager.clear();

        assertThatThrownBy(() -> {
            userRepository.deleteById(owner.getId());
            userRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRefusesToDeleteAProductReferencedByAnOrder() {
        Order order = new Order(owner, shipping);
        order.addItem(keyboard, 1);
        orderRepository.saveAndFlush(order);
        entityManager.clear();

        assertThatThrownBy(() -> {
            productRepository.deleteById(keyboard.getId());
            productRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theLockingLookupIsScopedToTheOwner() {
        Order order = new Order(owner, shipping);
        order.addItem(keyboard, 1);
        orderRepository.saveAndFlush(order);

        assertThat(orderRepository.findByIdAndUserIdForUpdate(order.getId(), owner.getId()))
                .isPresent();
        assertThat(orderRepository.findByIdAndUserIdForUpdate(order.getId(), other.getId()))
                .isEmpty();
    }

    @Test
    void statusIsStoredAsItsName() {
        Order order = new Order(owner, shipping);
        orderRepository.saveAndFlush(order);

        String status = (String) entityManager
                .createNativeQuery("SELECT status FROM orders WHERE id = :id")
                .setParameter("id", order.getId())
                .getSingleResult();

        assertThat(status).isEqualTo("PENDING");
    }

    private Product product(String sku, String name, String slug, String price) {
        return productRepository.saveAndFlush(new Product(
                category, sku, name, slug, "Description",
                new BigDecimal(price), 10, "https://example.com/" + slug + ".png"));
    }
}
