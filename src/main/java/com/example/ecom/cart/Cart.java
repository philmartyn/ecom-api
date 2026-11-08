package com.example.ecom.cart;

import com.example.ecom.catalog.Product;
import com.example.ecom.common.domain.AuditableEntity;
import com.example.ecom.user.User;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "carts")
public class Cart extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {}

    public Cart(User user) {
        this.user = user;
    }

    public long getId(){
        return id;
    }

    public User getUser() {
        return user;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public long quantityAfterAdding(Product product, int quantity) {
        requirePositive(quantity);
        return (long) quantityOf(product) + quantity;
    }

    public void addItem(Product product, int quantity) {
        int resulting = Math.toIntExact(quantityAfterAdding(product, quantity));

        findItem(product).ifPresentOrElse(
                item -> item.updateQuantity(resulting),
                () -> items.add(new CartItem(this, product, quantity)));
    }

    public boolean changeQuantity(Product product, int quantity) {
        requirePositive(quantity);

        return findItem(product)
                .map(item -> {
                    item.updateQuantity(quantity);
                    return true;
                })
                .orElse(false);
    }

    public boolean removeItem(Product product) {
        return findItem(product)
                .map(items::remove)
                .orElse(false);
    }

    public int quantityOf(Product product) {
        return findItem(product)
                .map(CartItem::getQuantity)
                .orElse(0);
    }

    public boolean containsProduct(Product product) {
        return findItem(product).isPresent();
    }

    private Optional<CartItem> findItem(Product product) {
        return items.stream()
                .filter(item -> sameProduct(item.getProduct(), product))
                .findFirst();
    }

    /**
     * In a request both Products come from the persistence context and have ids.
     * In a plain unit test no id has been assigned, so fall back to identity.
     */
    private static boolean sameProduct(Product left, Product right) {
        if (left.getId() != null && right.getId() != null) {
            return left.getId().equals(right.getId());
        }
        return left == right;
    }

    private static void requirePositive(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }
    }
}
