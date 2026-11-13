package com.example.ecom.payment;

import com.example.ecom.common.domain.AuditableEntity;
import com.example.ecom.order.Order;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Entity
@Table(name = "payments")
public class Payment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_ref", length = 255)
    private String providerRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name ="failure_code", length = 64)
    private String failureCode;

    protected Payment() {}

    public Payment(Order order, String provider) {
        this.order = order;
        this.provider = provider;
        this.status = PaymentStatus.PENDING;
        this.amount  = order.getTotal().setScale(2, RoundingMode.HALF_UP);
        this.currency = order.getCurrency();
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void attachProviderRef(String providerRef) {
        Objects.requireNonNull(providerRef, "providerRef");
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot attach a provider reference to a " + status + " payment");
        }
        if (this.providerRef == null) {
            this.providerRef = providerRef;
            return;
        }
        if (!this.providerRef.equals(providerRef)) {
            throw new IllegalStateException(
                    "Payment already has provider reference " + this.providerRef);
        }
    }

    public void markSucceeded() {
        if (status == PaymentStatus.SUCCEEDED) {
            return;
        }
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot succeed a " + status + " payment");
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.failureCode = null;
    }

    public void markFailed(String failureCode) {
        if (status == PaymentStatus.FAILED) {
            return;
        }
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot fail a " + status + " payment");
        }
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
    }

}
