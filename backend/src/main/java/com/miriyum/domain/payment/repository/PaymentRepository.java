package com.miriyum.domain.payment.repository;

import com.miriyum.domain.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findBySourceTypeAndSourceReferenceId(String sourceType, String sourceReferenceId);

    Optional<Payment> findBySourceTypeAndPreparationIdempotencyKey(
            String sourceType,
            String preparationIdempotencyKey
    );

    Optional<Payment> findByPaymentIdAndConsumerAccountId(String paymentId, Long consumerAccountId);

    Optional<Payment> findByPortOnePaymentId(String portOnePaymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.portOnePaymentId = :portOnePaymentId")
    Optional<Payment> findByPortOnePaymentIdForUpdate(
            @Param("portOnePaymentId") String portOnePaymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentId = :paymentId")
    Optional<Payment> findByPaymentIdForUpdate(@Param("paymentId") String paymentId);

    @Query("""
            select p from Payment p
            where p.consumerAccountId = :consumerAccountId
              and (:status is null or p.status = :status)
              and (:cursorCreatedAt is null
                   or p.createdAt < :cursorCreatedAt
                   or (p.createdAt = :cursorCreatedAt and p.paymentId < :cursorPaymentId))
            order by p.createdAt desc, p.paymentId desc
            """)
    List<Payment> findHistory(
            @Param("consumerAccountId") Long consumerAccountId,
            @Param("status") Payment.Status status,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorPaymentId") String cursorPaymentId,
            Pageable pageable
    );
}
