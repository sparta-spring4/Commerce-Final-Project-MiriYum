package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RegisterManualRecoveryHandoffCommand;
import com.miriyum.domain.reservation.entity.ReservationPaymentRecoveryOutbox;
import com.miriyum.domain.reservation.repository.ReservationPaymentRecoveryOutboxRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reservation 복구 outbox의 원자 enqueue와 짧은 lease transaction 경계다. */
@Service
public class ReservationPaymentRecoveryOutboxService {

    private final ReservationPaymentRecoveryOutboxRepository repository;
    private final Clock clock;
    private final Duration leaseDuration;

    public ReservationPaymentRecoveryOutboxService(
            ReservationPaymentRecoveryOutboxRepository repository,
            Clock clock,
            @Qualifier("reservationPaymentRecoveryHandoffLeaseDuration") Duration leaseDuration
    ) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.repository = repository;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(
            ManualRecoverySourceType sourceType,
            String sourceId,
            String paymentId,
            String sourceEventId,
            String idempotencyKey
    ) {
        ReservationPaymentRecoveryOutbox existing = repository
                .findBySourceTypeAndSourceIdForUpdate(sourceType, sourceId)
                .orElse(null);
        if (existing != null) {
            if (!existing.matches(paymentId, sourceEventId, idempotencyKey)) {
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }
            return;
        }
        repository.saveAndFlush(ReservationPaymentRecoveryOutbox.pending(
                sourceType, sourceId, paymentId, sourceEventId,
                idempotencyKey, clock.instant()));
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public List<Claim> claimDue(String owner, int limit) {
        if (owner == null || owner.isBlank() || owner.length() > 64) {
            throw new IllegalArgumentException("owner must be 1 to 64 characters");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Instant now = clock.instant();
        return repository.findClaimableForUpdate(now, PageRequest.of(0, limit))
                .stream()
                .map(outbox -> {
                    outbox.claim(owner, now, now.plus(leaseDuration));
                    repository.save(outbox);
                    return Claim.from(outbox, owner);
                })
                .toList();
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public boolean recordDelivered(Claim claim) {
        return update(claim, null);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public boolean recordRetry(Claim claim, Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        return update(claim, delay);
    }

    private boolean update(Claim claim, Duration retryDelay) {
        if (claim == null) {
            throw new IllegalArgumentException("claim must not be null");
        }
        ReservationPaymentRecoveryOutbox outbox = repository
                .findByIdForUpdate(claim.outboxId())
                .orElse(null);
        if (outbox == null || !claim.matches(outbox)) {
            return false;
        }
        try {
            if (retryDelay == null) {
                outbox.deliver(claim.owner(), claim.token(), clock.instant());
            } else {
                outbox.requeue(claim.owner(), claim.token(), clock.instant(), retryDelay);
            }
            repository.saveAndFlush(outbox);
            return true;
        } catch (IllegalStateException stale) {
            return false;
        }
    }

    public record Claim(
            long outboxId,
            ManualRecoverySourceType sourceType,
            String sourceId,
            String paymentId,
            String sourceEventId,
            String idempotencyKey,
            String owner,
            long token
    ) {
        private static Claim from(ReservationPaymentRecoveryOutbox outbox, String owner) {
            if (outbox.getId() == null || outbox.getId() <= 0) {
                throw new IllegalStateException("persisted recovery outbox id is required");
            }
            return new Claim(
                    outbox.getId(), outbox.getSourceType(), outbox.getSourceId(),
                    outbox.getPaymentId(), outbox.getSourceEventId(),
                    outbox.getDeliveryIdempotencyKey(), owner, outbox.getClaimToken());
        }

        public RegisterManualRecoveryHandoffCommand toCommand() {
            return new RegisterManualRecoveryHandoffCommand(
                    sourceType, sourceId, paymentId, sourceEventId, idempotencyKey);
        }

        private boolean matches(ReservationPaymentRecoveryOutbox outbox) {
            return outboxId == outbox.getId()
                    && sourceType == outbox.getSourceType()
                    && sourceId.equals(outbox.getSourceId())
                    && outbox.matches(paymentId, sourceEventId, idempotencyKey);
        }
    }
}
