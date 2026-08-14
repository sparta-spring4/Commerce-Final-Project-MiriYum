package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldTransitionAuditRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 만료 후보 조회와 후보별 독립 종결 명령을 transaction 밖에서 조정한다. */
@Service
@Transactional(propagation = Propagation.NEVER)
@Slf4j
public class ReservationHoldExpirationService {

    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final String EXPIRATION_COMMAND_PREFIX = "reservation-hold-expire:";
    private static final Duration RECONCILIATION_LONG_STAY = Duration.ofMinutes(10);

    private final ReservationHoldRepository holdRepository;
    private final ReservationHoldTransitionAuditRepository auditRepository;
    private final ReservationHoldCommandFacade commandFacade;
    private final Clock clock;

    public ReservationHoldExpirationService(
            ReservationHoldRepository holdRepository,
            ReservationHoldTransitionAuditRepository auditRepository,
            ReservationHoldCommandFacade commandFacade,
            Clock clock
    ) {
        this.holdRepository = Objects.requireNonNull(holdRepository);
        this.auditRepository = Objects.requireNonNull(auditRepository);
        this.commandFacade = Objects.requireNonNull(commandFacade);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 고정 cutoff의 모든 due 후보를 keyset으로 훑고 후보마다 새 종결 transaction을 호출한다.
     *
     * @param batchSize 페이지당 최대 후보 수
     * @return facade 호출이 정상 반환한 후보 수
     */
    public int expireDueHolds(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (Thread.currentThread().isInterrupted()) {
            return 0;
        }
        Instant cutoff = clock.instant();
        PageRequest page = PageRequest.of(0, batchSize);
        long afterId = 0L;
        int completed = 0;
        while (!Thread.currentThread().isInterrupted()) {
            List<ReservationHoldRepository.ExpirationCandidate> candidates =
                    holdRepository.findActiveExpirationCandidatesAfter(
                            cutoff, afterId, page);
            if (candidates == null || candidates.isEmpty()) {
                break;
            }
            for (ReservationHoldRepository.ExpirationCandidate candidate : candidates) {
                if (Thread.currentThread().isInterrupted()) {
                    return completed;
                }
                long candidateId = requireNextCandidate(candidate, afterId);
                afterId = candidateId;
                try {
                    commandFacade.transition(expirationCommand(candidate));
                    completed++;
                } catch (RuntimeException failure) {
                    log.warn(
                            "Reservation hold expiration candidate failed. reservationHoldId={}",
                            candidateId,
                            failure);
                }
            }
        }
        return completed;
    }

    /** 현재 RECONCILIATION_REQUIRED 장기 체류 그룹 수를 10분 포함 경계로 조회한다. */
    public long countLongStayingReconciliations() {
        Instant boundary = clock.instant().minus(RECONCILIATION_LONG_STAY);
        return auditRepository.countCurrentReconciliationRequiredAtOrBefore(
                boundary,
                ReservationHoldStatus.RECONCILIATION_REQUIRED);
    }

    private static long requireNextCandidate(
            ReservationHoldRepository.ExpirationCandidate candidate,
            long afterId
    ) {
        if (candidate == null
                || candidate.getReservationHoldId() == null
                || candidate.getReservationHoldId() <= afterId
                || candidate.getExpiresAt() == null) {
            throw new IllegalStateException("expiration candidates must advance by ID");
        }
        return candidate.getReservationHoldId();
    }

    private static ReservationHoldContracts.TransitionCommand expirationCommand(
            ReservationHoldRepository.ExpirationCandidate candidate
    ) {
        long holdId = candidate.getReservationHoldId();
        return new ReservationHoldContracts.TransitionCommand(
                holdId,
                ReservationHoldStatus.EXPIRED,
                EXPIRATION_COMMAND_PREFIX + holdId,
                SYSTEM_ACTOR,
                null,
                candidate.getExpiresAt());
    }
}
