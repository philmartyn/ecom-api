package com.example.ecom.dev;

import com.example.ecom.catalog.Category;
import com.example.ecom.catalog.CategoryRepository;
import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
@Profile("local")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public DevDataSeeder(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long existing = categoryRepository.count();
        if (existing > 0) {
            log.info("Catalog already seeded ({} categories), skipping", existing);
            return;
        }

        Category electronics = categoryRepository.save(
                new Category("Electronics", "electronics", "Phones, laptops and gadgets"));
        Category books = categoryRepository.save(
                new Category("Books", "books", "Paperbacks and technical references"));
        Category homeKitchen = categoryRepository.save(
                new Category("Home & Kitchen", "home-kitchen", "Cookware and small appliances"));
        Category sports = categoryRepository.save(
                new Category("Sports", "sports", "Fitness and outdoor equipment"));

        product(electronics, "SKU-EL-001", "Wireless Headphones", "wireless-headphones",
                "Over-ear Bluetooth headphones with 30 hours of battery life.", "129.99", 25);
        product(electronics, "SKU-EL-002", "Mechanical Keyboard", "mechanical-keyboard",
                "Tenkeyless layout with hot-swappable switches.", "89.50", 40);
        product(electronics, "SKU-EL-003", "4K Monitor", "4k-monitor",
                "27-inch IPS panel, 60 Hz, USB-C power delivery.", "349.00", 12);

        product(books, "SKU-BK-001", "Effective Java", "effective-java",
                "Best practices for the Java platform, third edition.", "45.00", 30);
        product(books, "SKU-BK-002", "Spring in Action", "spring-in-action",
                "A practical guide to Spring and Spring Boot.", "39.95", 20);

        product(homeKitchen, "SKU-HK-001", "Pour-Over Coffee Maker", "pour-over-coffee-maker",
                "Borosilicate glass dripper with a reusable steel filter.", "24.99", 50);
        product(homeKitchen, "SKU-HK-002", "Cast Iron Skillet", "cast-iron-skillet",
                "12-inch pre-seasoned skillet that improves with age.", "34.00", 15);
        product(homeKitchen, "SKU-HK-003", "Bamboo Cutting Board", "bamboo-cutting-board",
                "Reversible board with a juice groove. Out of stock for now.", "18.75", 0);

        product(sports, "SKU-SP-001", "Yoga Mat", "yoga-mat",
                "6 mm non-slip mat with alignment lines.", "29.99", 60);
        product(sports, "SKU-SP-002", "Adjustable Dumbbell Set", "adjustable-dumbbell-set",
                "5 to 52 lb per hand, sold as a pair.", "199.00", 8);

        log.info("Seeded {} categories and {} products",
                categoryRepository.count(), productRepository.count());
    }

    private void product(Category category, String sku, String name, String slug,
                         String description, String price, int stockQuantity) {
        productRepository.save(new Product(
                category, sku, name, slug, description,
                new BigDecimal(price), stockQuantity,
                "https://placehold.co/600x400?text=" + slug));
    }
}