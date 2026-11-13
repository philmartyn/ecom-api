package com.example.ecom.payment;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findFirstByOrderIdAndStatus(Long orderId, PaymentStatus status);

    boolean existsByOrderIdAndStatus(Long orderId, PaymentStatus status);

    Optional<Payment> findByProviderAndProviderRef(String provider, String providerRef);
    Optional<Payment> findFirstByProviderRef(String providerRef);

    List<Payment> findByOrderIdOrderByCreatedAtDescIdDesc(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    Optional<Payment> findByIdAndOrderId(Long id, Long orderId);

    /**
     * {@code FOR UPDATE SKIP LOCKED}: two replicas sweeping at once take disjoint
     * batches instead of blocking on each other. The {@code -2} timeout is
     * Hibernate's SKIP_LOCKED marker; there is no JPA constant for it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select p from Payment p
            where p.status = com.example.ecom.payment.PaymentStatus.PENDING
              and p.createdAt < :cutoff
            order by p.createdAt asc
            """)
    List<Payment> findExpiredPendingForUpdate(@Param("cutoff") Instant cutoff);

    /**
     * Only the foreign key: hydrating the Payment before the order lock would
     * put a possibly stale row in the persistence context, and the locked read
     * afterwards would hand back that same instance.
     */
    @Query("select p.order.id from Payment p where p.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

}
