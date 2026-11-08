package com.example.ecom.cart;

import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.Product;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Cart aggregate and its response mapping, without Spring or a database.
 * <p>
 * The entity owns the structural invariants: one line per Product, positive
 * Quantities, accumulate-on-add and absolute-on-change. It deliberately does
 * <em>not</em> know about stock or Active — those are catalog policy and live in
 * the service, which turns them into 404/409. Here we pin what "available"
 * means for an already-built line: the Product is Active and its stock is at
 * least the Quantity.
 * <p>
 * Money is always scale 2, including the empty cart, so a client never has to
 * guess whether 0 renders as {@code 0} or {@code 0.00}.
 */
class CartTest {

    private final Category category = new Category("Books", "books", "Paperbacks");
    private final User user = new User(
            "bob@example.com", "password-hash", "Bob", "Smith", Role.CUSTOMER);

    // --- structure ---------------------------------------------------------

    @Test
    void newCartIsEmpty() {
        Cart cart = new Cart(user);

        assertThat(cart.getUser()).isSameAs(user);
        assertThat(cart.getItems()).isEmpty();
    }

    @Test
    void addItemCreatesALineWithTheRequestedQuantity() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);

        cart.addItem(keyboard, 2);

        assertThat(cart.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProduct()).isSameAs(keyboard);
            assertThat(item.getQuantity()).isEqualTo(2);
        });
    }

    @Test
    void addingTheSameProductAgainAccumulatesInsteadOfDuplicating() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);

        cart.addItem(keyboard, 2);
        cart.addItem(keyboard, 3);

        assertThat(cart.getItems()).singleElement()
                .satisfies(item -> assertThat(item.getQuantity()).isEqualTo(5));
    }

    @Test
    void addingADifferentProductAddsASecondLine() {
        Cart cart = new Cart(user);

        cart.addItem(product("SKU-1", "Keyboard", "keyboard", "89.99", 10), 1);
        cart.addItem(product("SKU-2", "Mouse", "mouse", "39.99", 10), 1);

        assertThat(cart.getItems()).hasSize(2);
    }

    @Test
    void linesKeepInsertionOrderInMemory() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);
        Product mouse = product("SKU-2", "Mouse", "mouse", "39.99", 10);

        cart.addItem(keyboard, 1);
        cart.addItem(mouse, 1);

        // The database order is createdAt ASC, id ASC (see CartRepositoryTest);
        // in memory a new line is simply appended.
        assertThat(cart.getItems())
                .extracting(item -> item.getProduct().getSlug())
                .containsExactly("keyboard", "mouse");
    }

    // --- quantities --------------------------------------------------------

    @Test
    void addRejectsANonPositiveQuantity() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);

        assertThatThrownBy(() -> cart.addItem(keyboard, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cart.addItem(keyboard, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cart.getItems()).isEmpty();
    }

    @Test
    void changeQuantitySetsAnAbsoluteQuantity() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);
        cart.addItem(keyboard, 2);

        boolean changed = cart.changeQuantity(keyboard, 5);

        assertThat(changed).isTrue();
        assertThat(cart.getItems()).singleElement()
                .satisfies(item -> assertThat(item.getQuantity()).isEqualTo(5));
    }

    @Test
    void changeQuantityToTheSameValueIsStillASuccess() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);
        cart.addItem(keyboard, 2);

        assertThat(cart.changeQuantity(keyboard, 2)).isTrue();
        assertThat(cart.getItems()).singleElement()
                .satisfies(item -> assertThat(item.getQuantity()).isEqualTo(2));
    }

    @Test
    void changeQuantityReportsWhenTheProductIsNotInTheCart() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);

        assertThat(cart.changeQuantity(keyboard, 3)).isFalse();
        assertThat(cart.getItems()).isEmpty();
    }

    @Test
    void changeQuantityRejectsANonPositiveQuantity() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);
        cart.addItem(keyboard, 2);

        assertThatThrownBy(() -> cart.changeQuantity(keyboard, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cart.changeQuantity(keyboard, -3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cart.getItems()).singleElement()
                .satisfies(item -> assertThat(item.getQuantity()).isEqualTo(2));
    }

    @Test
    void removeItemReportsWhetherALineWasRemoved() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);
        Product mouse = product("SKU-2", "Mouse", "mouse", "39.99", 10);
        cart.addItem(keyboard, 1);
        cart.addItem(mouse, 2);

        assertThat(cart.removeItem(keyboard)).isTrue();
        assertThat(cart.getItems()).singleElement()
                .satisfies(item -> assertThat(item.getProduct().getSlug()).isEqualTo("mouse"));

        assertThat(cart.removeItem(keyboard)).isFalse();
    }

    // --- response mapping --------------------------------------------------

    @Test
    void emptyResponseHasZeroTotalsAtScaleTwo() {
        CartResponse response = CartResponse.from(new Cart(user));

        assertThat(response.items()).isEmpty();
        assertThat(response.totalQuantity()).isZero();
        assertThat(response.subtotal()).isEqualByComparingTo("0.00");
        assertThat(response.subtotal().scale()).isEqualTo(2);
    }

    @Test
    void totalQuantityCountsUnitsNotLines() {
        Cart cart = new Cart(user);
        cart.addItem(product("SKU-1", "Keyboard", "keyboard", "89.99", 10), 2);
        cart.addItem(product("SKU-2", "Mouse", "mouse", "39.99", 10), 3);

        CartResponse response = CartResponse.from(cart);

        assertThat(response.totalQuantity()).isEqualTo(5);
        assertThat(response.items()).hasSize(2);
    }

    @Test
    void lineTotalIsPriceTimesQuantityAtScaleTwo() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);
        cart.addItem(keyboard, 2);

        CartResponse response = CartResponse.from(cart);
        CartItemResponse line = response.items().getFirst();

        assertThat(line.lineTotal()).isEqualByComparingTo("179.98");
        assertThat(line.lineTotal().scale()).isEqualTo(2);
        assertThat(response.subtotal()).isEqualByComparingTo("179.98");
        assertThat(response.subtotal().scale()).isEqualTo(2);
    }

    @Test
    void subtotalIsTheSumOfLineTotals() {
        Cart cart = new Cart(user);
        cart.addItem(product("SKU-1", "Keyboard", "keyboard", "89.99", 10), 2);
        cart.addItem(product("SKU-2", "Mouse", "mouse", "39.99", 10), 3);

        CartResponse response = CartResponse.from(cart);

        // 2 * 89.99 + 3 * 39.99 = 179.98 + 119.97
        assertThat(response.subtotal()).isEqualByComparingTo("299.95");
        assertThat(response.subtotal().scale()).isEqualTo(2);
    }

    @Test
    void lineIsAvailableWhenTheProductIsActiveAndStockCoversTheQuantity() {
        Cart cart = new Cart(user);
        cart.addItem(product("SKU-1", "Keyboard", "keyboard", "89.99", 2), 2);

        assertThat(CartResponse.from(cart).items().getFirst().available()).isTrue();
    }

    @Test
    void lineIsUnavailableWhenStockIsBelowTheQuantity() {
        Cart cart = new Cart(user);
        // Stock can change after the line was added; the entity stores the line
        // even when it can no longer be fulfilled.
        cart.addItem(product("SKU-1", "Keyboard", "keyboard", "89.99", 1), 2);

        assertThat(CartResponse.from(cart).items().getFirst().available()).isFalse();
    }

    @Test
    void lineIsUnavailableWhenTheProductHasBeenDeactivated() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);
        cart.addItem(keyboard, 1);

        keyboard.deactivate();

        CartResponse response = CartResponse.from(cart);
        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.available()).isFalse());
    }

    @Test
    void productSummaryCarriesOnlyWhatACartNeeds() {
        Cart cart = new Cart(user);
        Product keyboard = product("SKU-1", "Keyboard", "keyboard", "89.99", 10);
        cart.addItem(keyboard, 1);

        CartProductResponse product = CartResponse.from(cart).items().getFirst().product();

        assertThat(product.slug()).isEqualTo("keyboard");
        assertThat(product.name()).isEqualTo("Keyboard");
        assertThat(product.price()).isEqualByComparingTo("89.99");
        assertThat(product.imageUrl()).isEqualTo("https://example.com/keyboard.png");
    }

    private Product product(String sku, String name, String slug, String price, int stock) {
        return new Product(category, sku, name, slug, "Description",
                new BigDecimal(price), stock, "https://example.com/keyboard.png");
    }
}
