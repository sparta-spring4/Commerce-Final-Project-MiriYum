package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingConversionCompensation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitingConversionCompensationRepository
        extends JpaRepository<WaitingConversionCompensation, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO waiting_conversion_compensations (
                waiting_team_id,
                payment_id,
                refund_amount_minor,
                currency,
                refund_policy_version,
                source_event_id,
                idempotency_key,
                reason_code,
                status,
                attempt_count,
                next_attempt_at,
                claim_token,
                created_at
            ) VALUES (
                :waitingTeamId,
                :paymentId,
                :refundAmountMinor,
                :currency,
                :refundPolicyVersion,
                :sourceEventId,
                :idempotencyKey,
                :reasonCode,
                'PENDING',
                0,
                :now,
                0,
                :now
            )
            ON DUPLICATE KEY UPDATE
                waiting_conversion_compensation_id = waiting_conversion_compensation_id
            """, nativeQuery = true)
    int insertRequired(
            @Param("waitingTeamId") long waitingTeamId,
            @Param("paymentId") String paymentId,
            @Param("refundAmountMinor") long refundAmountMinor,
            @Param("currency") String currency,
            @Param("refundPolicyVersion") long refundPolicyVersion,
            @Param("sourceEventId") String sourceEventId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("reasonCode") String reasonCode,
            @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select compensation
            from WaitingConversionCompensation compensation
            where compensation.waitingTeamId = :waitingTeamId
              and compensation.paymentId = :paymentId
            """)
    Optional<WaitingConversionCompensation> findByWaitingTeamIdAndPaymentIdForUpdate(
            @Param("waitingTeamId") long waitingTeamId,
            @Param("paymentId") String paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select compensation
            from WaitingConversionCompensation compensation
            where compensation.id = :id
            """)
    Optional<WaitingConversionCompensation> findByIdForUpdate(@Param("id") long id);

    @Query(value = """
            SELECT *
            FROM waiting_conversion_compensations
            WHERE (
                (status = :pending AND next_attempt_at <= :now)
                OR (status = :processing AND lease_until <= :now)
            )
              AND waiting_conversion_compensation_id > :afterId
            ORDER BY waiting_conversion_compensation_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WaitingConversionCompensation> findClaimableForUpdate(
            @Param("pending") String pending,
            @Param("processing") String processing,
            @Param("now") Instant now,
            @Param("afterId") long afterId,
            @Param("limit") int limit);
}
