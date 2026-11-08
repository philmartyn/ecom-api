package com.example.ecom.cart;

import com.example.ecom.catalog.Product;
import com.example.ecom.common.domain.AuditableEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "cart_items")
public class CartItem extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false)
    private int quantity;

    protected CartItem() {}

    public CartItem(Cart cart, Product product, int quantity) {
        this.cart = cart;
        this.product = product;
        this.quantity = quantity;
    }

    public long getId() {
        return id;
    }

    public Cart getCart() {
        return cart;
    }

    public Product getProduct() {
    return product;
    }

    public int getQuantity(){
        return quantity;
    }

    protected void updateQuantity(int quantity){
        this.quantity = quantity;
    }
}
