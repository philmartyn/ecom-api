package com.example.ecom.order;

import com.example.ecom.catalog.Product;
import com.example.ecom.common.domain.AuditableEntity;
import com.example.ecom.common.exception.OrderNotCancellableException;
import com.example.ecom.common.exception.OrderNotPayableException;
import com.example.ecom.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static com.example.ecom.common.helpers.Numbers.BIG_DECIMAL_ZERO;

@Entity
@Table(name = "orders")
public class Order extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, length = 3)
    private String currency;

    @Embedded
    private ShippingAddress shippingAddress;

    @BatchSize(size = 50)
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    public Order (User user, ShippingAddress shippingAddress) {
        this.user = user;
        this.shippingAddress = shippingAddress;
        this.status = OrderStatus.PENDING;
        this.currency = "USD";
        this.subtotal = BIG_DECIMAL_ZERO;
        this.total = BIG_DECIMAL_ZERO;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getCurrency() {
        return currency;
    }

    public ShippingAddress getShippingAddress() {
        return shippingAddress;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public int getTotalQuantity() {
        return items.stream()
                .mapToInt(OrderItem::getQuantity)
                .sum();
    }

    public void addItem(Product product, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }

        items.add(new OrderItem(this, product, quantity));
        recalculateTotal();
    }

    public void cancel() {
        if (status == OrderStatus.CANCELLED) {
            return;
        }
        if (status != OrderStatus.PENDING) {
            throw new OrderNotCancellableException(status.name());
        }
        this.status = OrderStatus.CANCELLED;
    }

    private void recalculateTotal() {
        BigDecimal sum = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BIG_DECIMAL_ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        this.subtotal = sum;
        this.total = sum;
    }

    public void markPaid() {
        if (status == OrderStatus.PAID) {
            return;
        }
        if (status != OrderStatus.PENDING) {
            throw new OrderNotPayableException(status);
        }
        this.status = OrderStatus.PAID;
    }
}
