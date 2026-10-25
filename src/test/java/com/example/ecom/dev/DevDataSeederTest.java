package com.example.ecom.dev;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.CategoryRepository;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.common.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class DevDataSeederTest extends AbstractIntegrationTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void seedsFourCategoriesAndTenProducts() {
        // Constructed by hand, so @Transactional on run() is NOT applied here.
        // The @DataJpaTest transaction wraps the whole test instead, which is
        // sufficient: every insert commits or rolls back together either way.
        seeder().run(new DefaultApplicationArguments());

        assertThat(categoryRepository.count()).isEqualTo(4);
        assertThat(productRepository.count()).isEqualTo(10);
    }

    @Test
    void runningTwiceDoesNotDuplicateAnything() {
        seeder().run(new DefaultApplicationArguments());
        seeder().run(new DefaultApplicationArguments());

        assertThat(categoryRepository.count()).isEqualTo(4);
        assertThat(productRepository.count()).isEqualTo(10);
    }

    @Test
    void skipsEntirelyWhenTheCatalogAlreadyHasCategories() {
        categoryRepository.saveAndFlush(new Category("Existing", "existing", null));

        seeder().run(new DefaultApplicationArguments());

        assertThat(categoryRepository.count()).isEqualTo(1);
        assertThat(productRepository.count()).isZero();
    }

    private DevDataSeeder seeder() {
        return new DevDataSeeder(categoryRepository, productRepository);
    }
}
