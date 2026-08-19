package com.miriyum.domain.payment.repository;

import com.miriyum.domain.payment.dto.PaymentContracts.DispositionFailureClassification;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
import com.miriyum.domain.payment.entity.ReservationDepositDisposition;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationDepositDispositionRepository
        extends JpaRepository<ReservationDepositDisposition, Long> {

    Optional<ReservationDepositDisposition> findByPayment_IdAndIdempotencyKey(
            Long paymentId,
            String idempotencyKey
    );

    Optional<ReservationDepositDisposition> findByPayment_IdAndSourceEventId(
            Long paymentId,
            String sourceEventId
    );

    Optional<ReservationDepositDisposition> findByPayment_PaymentIdAndSourceEventId(
            String paymentId,
            String sourceEventId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from ReservationDepositDisposition d join fetch d.payment
            where d.payment.id = :paymentId and d.sourceEventId = :sourceEventId
            """)
    Optional<ReservationDepositDisposition> findByPayment_IdAndSourceEventIdForUpdate(
            @Param("paymentId") Long paymentId,
            @Param("sourceEventId") String sourceEventId);

    @Query("""
            select case when count(d) > 0 then true else false end
            from ReservationDepositDisposition d
            where d.payment.id = :paymentId
              and d.correctsSourceEventId = :correctsSourceEventId
              and (d.status <> :failedStatus
                   or d.failureClassification is null
                   or d.failureClassification <> :permanentFailure)
            """)
    boolean existsLineageChild(
            @Param("paymentId") Long paymentId,
            @Param("correctsSourceEventId") String correctsSourceEventId,
            @Param("failedStatus") DispositionStatus failedStatus,
            @Param("permanentFailure") DispositionFailureClassification permanentFailure
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from ReservationDepositDisposition d
            join fetch d.payment
            where d.dispositionId = :dispositionId
            """)
    Optional<ReservationDepositDisposition> findByDispositionIdForUpdate(
            @Param("dispositionId") String dispositionId
    );
}
