package com.miriyum.domain.payment.repository;

import com.miriyum.domain.payment.entity.PaymentRefund;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {
    Optional<PaymentRefund> findByPayment_IdAndIdempotencyKey(Long paymentId, String idempotencyKey);
    Optional<PaymentRefund> findByPayment_IdAndSourceEventId(Long paymentId, String sourceEventId);
    List<PaymentRefund> findByPayment_IdOrderByRequestedAtAsc(Long paymentId);
    boolean existsByPayment_IdAndProviderCancellationIdAndStatus(
            Long paymentId,
            String providerCancellationId,
            com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus status
    );
    boolean existsByPayment_IdAndStatusIn(
            Long paymentId,
            Collection<com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus> statuses
    );

    @Query("""
            select coalesce(sum(r.amountMinor), 0)
            from PaymentRefund r
            where r.payment.id = :paymentId and r.status = :status
            """)
    long sumAmountMinorByPaymentIdAndStatus(
            @Param("paymentId") Long paymentId,
            @Param("status") com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRefund r join fetch r.payment where r.refundId = :refundId")
    Optional<PaymentRefund> findByRefundIdForUpdate(@Param("refundId") String refundId);
}
