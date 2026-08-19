package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType;
import com.miriyum.domain.reservation.entity.ReservationPaymentRecoveryOutbox;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationPaymentRecoveryOutboxRepository
        extends JpaRepository<ReservationPaymentRecoveryOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select outbox from ReservationPaymentRecoveryOutbox outbox
            where outbox.sourceType = :sourceType and outbox.sourceId = :sourceId
            """)
    Optional<ReservationPaymentRecoveryOutbox> findBySourceTypeAndSourceIdForUpdate(
            @Param("sourceType") ManualRecoverySourceType sourceType,
            @Param("sourceId") String sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select outbox from ReservationPaymentRecoveryOutbox outbox
            where (outbox.status = com.miriyum.domain.reservation.entity.ReservationPaymentRecoveryOutbox.Status.PENDING
                   and outbox.nextAttemptAt <= :now)
               or (outbox.status = com.miriyum.domain.reservation.entity.ReservationPaymentRecoveryOutbox.Status.PROCESSING
                   and outbox.leaseUntil <= :now)
            order by outbox.id asc
            """)
    List<ReservationPaymentRecoveryOutbox> findClaimableForUpdate(
            @Param("now") Instant now,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from ReservationPaymentRecoveryOutbox outbox where outbox.id = :id")
    Optional<ReservationPaymentRecoveryOutbox> findByIdForUpdate(@Param("id") long id);
}
