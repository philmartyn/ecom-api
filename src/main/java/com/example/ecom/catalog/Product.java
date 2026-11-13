package com.example.ecom.catalog;

import com.example.ecom.common.domain.AuditableEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product  extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name= "category_id",nullable = false)
    private Category category;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 220)
    private String slug;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private boolean active = true;

    protected Product() {}

    public Product(Category category, String sku, String name,
                   String slug, String description,
                   BigDecimal price, int stockQuantity, String imageUrl) {
        this.category = category;
        this.sku = sku;
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.imageUrl = imageUrl;
    }

    public Long getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isActive() {
        return active;
    }

    /** Soft delete: catalog history keeps referencing this row, so it is never removed. */
    public void deactivate() {
        this.active = false;
    }

    /**
     * Takes stock for a committed reservation. The service has already checked
     * availability under the row lock; this guard is the second line of defence
     * so a future caller cannot drive stock negative.
     */
    public void reserve(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }
        if (stockQuantity < quantity) {
            throw new IllegalStateException(
                    "Cannot reserve " + quantity + " of " + sku
                            + "; only " + stockQuantity + " left");
        }
        this.stockQuantity -= quantity;
    }

    /** Returns stock when an attempt fails, is cancelled, or expires. */
    public void restore(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }
        this.stockQuantity += quantity;
    }
}
