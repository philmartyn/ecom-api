package com.example.ecom.catalog;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.common.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// @DataJpaTest only picks up entities and repositories, so the auditing config has to
// be imported explicitly - otherwise created_at/updated_at are null on insert.
@Import(JpaAuditingConfig.class)
class CatalogRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void auditingPopulatesBothTimestamps() {
        Category saved = categoryRepository.saveAndFlush(category("books", "books"));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByActiveTrueExcludesInactiveProducts() {
        Category category = categoryRepository.saveAndFlush(category("books", "books"));

        productRepository.saveAndFlush(product(category, "SKU-1", "Active", 10));
        Product inactive = product(category, "SKU-2", "Inactive", 20);
        inactive.deactivate();
        productRepository.saveAndFlush(inactive);

        Page<Product> page = productRepository.findByActiveTrue(PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(Product::getSku).containsExactly("SKU-1");
    }

    @Test
    void findByActiveTruePaginates() {
        Category category = categoryRepository.saveAndFlush(category("books", "books"));
        for (int i = 1; i <= 5; i++) {
            productRepository.save(product(category, "SKU-" + i, "Product " + i, 10));
        }
        productRepository.flush();

        Page<Product> firstPage = productRepository.findByActiveTrue(PageRequest.of(0, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.getContent()).hasSize(2);
    }

    @Test
    void existsBySkuMatchesTheUniqueSku() {
        Category category = categoryRepository.saveAndFlush(category("books", "books"));
        productRepository.saveAndFlush(product(category, "SKU-1", "Name", 10));

        assertThat(productRepository.existsBySku("SKU-1")).isTrue();
        assertThat(productRepository.existsBySku("SKU-999")).isFalse();
    }

    @Test
    void findBySlugReturnsTheProduct() {
        Category category = categoryRepository.saveAndFlush(category("books", "books"));
        productRepository.saveAndFlush(product(category, "SKU-1", "Wireless Headphones", 10));

        assertThat(productRepository.findBySlug("wireless-headphones"))
                .isPresent()
                .get()
                .extracting(Product::getSku)
                .isEqualTo("SKU-1");
    }

    @Test
    void uniqueSkuIsEnforcedByTheDatabase() {
        Category category = categoryRepository.saveAndFlush(category("books", "books"));
        productRepository.saveAndFlush(product(category, "SKU-1", "First", 10));

        assertThatThrownBy(() -> productRepository.saveAndFlush(product(category, "SKU-1", "Second", 10)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueSlugIsEnforcedByTheDatabase() {
        categoryRepository.saveAndFlush(category("books", "books"));

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(category("More books", "books")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void categoryWithProductsCannotBeDeleted() {
        Category category = categoryRepository.saveAndFlush(category("books", "books"));
        productRepository.saveAndFlush(product(category, "SKU-1", "Name", 10));

        // ON DELETE RESTRICT protects products that customers may have bought.
        // Note: going through the repository would fail earlier, in Hibernate, with
        // TransientPropertyValueException - JPA refuses to leave a Product pointing
        // at a removed Category. A native statement bypasses that check and lets the
        // database constraint itself be the thing under test.
        assertThatThrownBy(() -> entityManager
                .createNativeQuery("DELETE FROM categories WHERE id = :id")
                .setParameter("id", category.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void productPriceKeepsTwoDecimalPlacesInTheDatabase() {
        Category category = categoryRepository.saveAndFlush(category("books", "books"));
        Product saved = productRepository.saveAndFlush(new Product(
                category, "SKU-1", "Name", "name", null, new BigDecimal("10"), 10, null));

        entityManager.clear();
        Product reloaded = productRepository.findById(saved.getId()).orElseThrow();

        // NUMERIC(12,2) rounds and pads on the way in; a fresh read proves it.
        assertThat(reloaded.getPrice()).isEqualByComparingTo("10.00");
        assertThat(reloaded.getPrice().scale()).isEqualTo(2);
    }

    private Category category(String name, String slug) {
        return new Category(name, slug, null);
    }

    private Product product(Category category, String sku, String name, int stock) {
        return new Product(category, sku, name, name.toLowerCase().replace(' ', '-'),
                null, new BigDecimal("10.00"), stock, null);
    }
}
