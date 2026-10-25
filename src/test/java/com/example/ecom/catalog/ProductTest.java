package com.example.ecom.catalog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTest {

    private final Category category = new Category("Books", "books", "Paperbacks");

    @Test
    void newProductIsActive() {
        Product product = product("19.99", 5);

        assertThat(product.isActive()).isTrue();
    }

    @Test
    void deactivateFlipsActiveWithoutTouchingOtherFields() {
        Product product = product("19.99", 5);

        product.deactivate();

        assertThat(product.isActive()).isFalse();
        assertThat(product.getPrice()).isEqualByComparingTo("19.99");
        assertThat(product.getStockQuantity()).isEqualTo(5);
    }

    @Test
    void keepsPriceScaleOfTwo() {
        Product product = product("19.90", 1);

        assertThat(product.getPrice().scale()).isEqualTo(2);
        assertThat(product.getPrice()).isEqualByComparingTo("19.90");
    }

    @Test
    void copiesEveryConstructorArgument() {
        Product product = new Product(category, "SKU-1", "Name", "name", "Description",
                new BigDecimal("12.34"), 7, "https://example.com/img.png");

        assertThat(product.getCategory()).isSameAs(category);
        assertThat(product.getSku()).isEqualTo("SKU-1");
        assertThat(product.getName()).isEqualTo("Name");
        assertThat(product.getSlug()).isEqualTo("name");
        assertThat(product.getDescription()).isEqualTo("Description");
        assertThat(product.getPrice()).isEqualByComparingTo("12.34");
        assertThat(product.getStockQuantity()).isEqualTo(7);
        assertThat(product.getImageUrl()).isEqualTo("https://example.com/img.png");
        assertThat(product.getId()).isNull();
    }

    private Product product(String price, int stock) {
        return new Product(category, "SKU-1", "Name", "name", null,
                new BigDecimal(price), stock, null);
    }
}
