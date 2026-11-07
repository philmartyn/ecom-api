package com.example.ecom.catalog;

import com.example.ecom.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalog behaviour against real PostgreSQL: the real filter chain, controller,
 * service and JPA specifications.
 * <p>
 * The fixture is deliberately small but covers the cases that are easy to get
 * wrong: a name that starts with the search term, a name that merely contains
 * it, an inactive product that must disappear, an out-of-stock product that
 * must still be listed, and a category with no matching products.
 * <p>
 * {@code @Transactional} rolls the fixture back after each test, so the
 * singleton PostgreSQL container stays clean without truncation code.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CatalogIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    private Category keyboards;
    private Category mice;

    @BeforeEach
    void seedCatalog() {
        keyboards = categoryRepository.saveAndFlush(
                new Category("Keyboards", "keyboards", "Input devices"));
        mice = categoryRepository.saveAndFlush(
                new Category("Mice", "mice", "Pointing devices"));

        save(keyboards, "SKU-KB-1", "Mechanical Keyboard", "mechanical-keyboard",
                "Clicky keys", "129.99", 10);
        save(keyboards, "SKU-KB-2", "Ergonomic Keyboard", "ergonomic-keyboard",
                "Comfortable", "89.50", 0);
        save(keyboards, "SKU-KB-3", "Keyboard", "keyboard",
                "Basic", "29.99", 5);

        Product retired = product(keyboards, "SKU-KB-4", "Retired Keyboard",
                "retired-keyboard", "Gone", "59.99", 3);
        retired.deactivate();
        productRepository.saveAndFlush(retired);

        // The description mentions "keyboard" on purpose: search must read the
        // name only, so this product must not appear in the keyboard search.
        save(mice, "SKU-MS-1", "Wireless Mouse", "wireless-mouse",
                "Pairs with any keyboard", "39.99", 7);
    }

    @Test
    void listsOnlyActiveProductsByDefault() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[*].slug", containsInAnyOrder(
                        "ergonomic-keyboard", "keyboard",
                        "mechanical-keyboard", "wireless-mouse")))
                .andExpect(jsonPath("$.items[0].sku").doesNotExist())
                .andExpect(jsonPath("$.items[0].stockQuantity").doesNotExist());
    }

    @Test
    void sortsByNameByDefault() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].slug", contains(
                        "ergonomic-keyboard", "keyboard",
                        "mechanical-keyboard", "wireless-mouse")));
    }

    @Test
    void searchMatchesPartOfTheNameCaseInsensitively() throws Exception {
        mockMvc.perform(get("/api/products").param("q", "KEYBOARD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].slug", containsInAnyOrder(
                        "ergonomic-keyboard", "keyboard", "mechanical-keyboard")));
    }

    @Test
    void searchTreatsWildcardCharactersLiterally() throws Exception {
        save(mice, "SKU-MAT-1", "100% Cotton Mouse Mat", "cotton-mouse-mat",
                "Absorbent", "19.99", 4);

        mockMvc.perform(get("/api/products").param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("cotton-mouse-mat"));
    }

    @Test
    void filtersByCategorySlug() throws Exception {
        mockMvc.perform(get("/api/products").param("category", "mice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("wireless-mouse"));
    }

    @Test
    void unknownCategoryYieldsAnEmptyPage() throws Exception {
        mockMvc.perform(get("/api/products").param("category", "nope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void paginatesAndReportsTotals() throws Exception {
        mockMvc.perform(get("/api/products")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void aPagePastTheEndIsEmptyNot404() throws Exception {
        mockMvc.perform(get("/api/products")
                        .param("page", "9")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void sortsByPriceDescending() throws Exception {
        mockMvc.perform(get("/api/products").param("sort", "price,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].slug", contains(
                        "mechanical-keyboard", "ergonomic-keyboard",
                        "wireless-mouse", "keyboard")));
    }

    @Test
    void rejectsAnUnknownSortField() throws Exception {
        mockMvc.perform(get("/api/products").param("sort", "sku,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/products"));
    }

    @Test
    void returnsProductDetailBySlug() throws Exception {
        mockMvc.perform(get("/api/products/mechanical-keyboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("mechanical-keyboard"))
                .andExpect(jsonPath("$.name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.price").value(129.99))
                .andExpect(jsonPath("$.category.slug").value("keyboards"))
                .andExpect(jsonPath("$.category.name").value("Keyboards"))
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.description").value("Clicky keys"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.sku").doesNotExist())
                .andExpect(jsonPath("$.stockQuantity").doesNotExist());
    }

    @Test
    void marksAnOutOfStockProductAsNotInStock() throws Exception {
        mockMvc.perform(get("/api/products/ergonomic-keyboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(false))
                .andExpect(jsonPath("$.stockQuantity").doesNotExist());
    }

    @Test
    void returns404ForAnUnknownSlug() throws Exception {
        mockMvc.perform(get("/api/products/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Product not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Product not found"))
                .andExpect(jsonPath("$.instance").value("/api/products/nope"));
    }

    @Test
    void returns404ForAnInactiveProduct() throws Exception {
        mockMvc.perform(get("/api/products/retired-keyboard"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"))
                .andExpect(jsonPath("$.instance").value("/api/products/retired-keyboard"));
    }

    @Test
    void listsAllCategoriesOrderedByName() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].name", contains("Keyboards", "Mice")))
                .andExpect(jsonPath("$[*].slug", contains("keyboards", "mice")))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].description").value("Input devices"))
                .andExpect(jsonPath("$[0].productCount").doesNotExist());
    }

    private Product save(Category category, String sku, String name, String slug,
                         String description, String price, int stock) {
        return productRepository.saveAndFlush(
                product(category, sku, name, slug, description, price, stock));
    }

    private Product product(Category category, String sku, String name, String slug,
                            String description, String price, int stock) {
        return new Product(category, sku, name, slug, description,
                new BigDecimal(price), stock, null);
    }
}
