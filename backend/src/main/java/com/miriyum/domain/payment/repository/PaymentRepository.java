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

    @Query("""
            select p.paymentId as paymentId,
                   p.sourceType as sourceType,
                   p.sourceReferenceId as sourceReferenceId,
                   p.status as status,
                   p.version as version,
                   p.createdAt as createdAt,
                   p.updatedAt as updatedAt,
                   p.amountMinor as amountMinor,
                   p.refundedAmountMinor as refundedAmountMinor,
                   p.currency as currency
              from Payment p
             where p.updatedAt between :changedFrom and :changedTo
             order by p.updatedAt desc, p.paymentId desc
            """)
    List<MonitoringSnapshot> findMonitoringChanges(
            @Param("changedFrom") Instant changedFrom,
            @Param("changedTo") Instant changedTo,
            Pageable pageable);

    @Query("""
            select p.paymentId as paymentId,
                   p.sourceType as sourceType,
                   p.sourceReferenceId as sourceReferenceId,
                   p.status as status,
                   p.version as version,
                   p.createdAt as createdAt,
                   p.updatedAt as updatedAt,
                   p.amountMinor as amountMinor,
                   p.refundedAmountMinor as refundedAmountMinor,
                   p.currency as currency
              from Payment p
             where (p.sourceType = 'RESERVATION_DEPOSIT'
                    and p.sourceReferenceId in :reservationReferences)
                or (p.sourceType = 'WAITING_RESERVATION_DEPOSIT'
                    and p.sourceReferenceId in :waitingReferences)
             order by p.updatedAt desc, p.paymentId desc
            """)
    List<MonitoringSnapshot> findMonitoringSnapshots(
            @Param("reservationReferences") List<String> reservationReferences,
            @Param("waitingReferences") List<String> waitingReferences);

    @Query("""
            select count(payment)
            from Payment payment
            where payment.sourceType = :sourceType
              and payment.sourceReferenceId in :sourceReferenceIds
              and payment.status <> :#{T(com.miriyum.domain.payment.entity.Payment.Status).REFUNDED}
            """)
    long countUnsettledBySourceReferences(
            @Param("sourceType") String sourceType,
            @Param("sourceReferenceIds") List<String> sourceReferenceIds
    );

    @Query("""
            select payment.paymentId from Payment payment
            where payment.sourceType = :sourceType
              and payment.sourceReferenceId in :sourceReferenceIds
              and payment.status <> :#{T(com.miriyum.domain.payment.entity.Payment.Status).REFUNDED}
            order by payment.paymentId
            """)
    List<String> findUnsettledIdsBySourceReferences(@Param("sourceType") String sourceType,
            @Param("sourceReferenceIds") List<String> sourceReferenceIds);

    Optional<Payment> findBySourceTypeAndSourceReferenceId(String sourceType, String sourceReferenceId);

    Optional<Payment> findBySourceTypeAndPreparationIdempotencyKey(
            String sourceType,
            String preparationIdempotencyKey
    );

    Optional<Payment> findByPaymentIdAndConsumerAccountId(String paymentId, Long consumerAccountId);

    Optional<Payment> findByPaymentIdAndSourceTypeAndSourceReferenceId(
            String paymentId,
            String sourceType,
            String sourceReferenceId
    );

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

    interface MonitoringSnapshot {
        String getPaymentId();
        String getSourceType();
        String getSourceReferenceId();
        Payment.Status getStatus();
        long getVersion();
        Instant getCreatedAt();
        Instant getUpdatedAt();
        long getAmountMinor();
        long getRefundedAmountMinor();
        String getCurrency();
    }
}
