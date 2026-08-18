package com.miriyum.domain.reservation.service;

import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochService;
import com.miriyum.domain.reservation.dto.response.ReservationDepositDispositionResponse;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCheckInAudit;
import com.miriyum.domain.reservation.entity.ReservationCheckInQrGrant;
import com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.entity.ReservationFulfillmentActorType;
import com.miriyum.domain.reservation.entity.ReservationFulfillmentAudit;
import com.miriyum.domain.reservation.entity.ReservationNoShowAudit;
import com.miriyum.domain.reservation.entity.ReservationNoShowReason;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldItemSnapshot;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldTerminationPresence;
import com.miriyum.domain.reservation.repository.ReservationCheckInAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationCheckInQrGrantRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositDispositionObligationRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationFulfillmentAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationNoShowAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** QR scan과 운영자 no-show의 권한·멱등·잠금·MenuHold 종결을 조정한다. */
@Service
public class ReservationVisitService {

    private static final long CHECK_IN_WINDOW_SECONDS = 300L;
    private static final String SUCCESS = "SUCCESS";

    private final StoreService storeService;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ReservationRepository reservationRepository;
    private final ReservationCheckInQrGrantRepository grantRepository;
    private final ConsumerQrEpochService epochService;
    private final ReservationMenuHoldPort menuHoldPort;
    private final ReservationFulfillmentAuditRepository fulfillmentAuditRepository;
    private final ReservationCheckInAuditRepository checkInAuditRepository;
    private final ReservationNoShowAuditRepository noShowAuditRepository;
    private final ReservationDepositProcessRepository depositProcessRepository;
    private final ReservationDepositDispositionObligationRepository
            dispositionObligationRepository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ReservationVisitService(
            StoreService storeService,
            IdempotencyExecutor idempotencyExecutor,
            ReservationRepository reservationRepository,
            ReservationCheckInQrGrantRepository grantRepository,
            ConsumerQrEpochService epochService,
            ReservationMenuHoldPort menuHoldPort,
            ReservationFulfillmentAuditRepository fulfillmentAuditRepository,
            ReservationCheckInAuditRepository checkInAuditRepository,
            ReservationNoShowAuditRepository noShowAuditRepository,
            ReservationDepositProcessRepository depositProcessRepository,
            ReservationDepositDispositionObligationRepository dispositionObligationRepository,
            Clock clock,
            ObjectMapper objectMapper
    ) {
        this.storeService = storeService;
        this.idempotencyExecutor = idempotencyExecutor;
        this.reservationRepository = reservationRepository;
        this.grantRepository = grantRepository;
        this.epochService = epochService;
        this.menuHoldPort = menuHoldPort;
        this.fulfillmentAuditRepository = fulfillmentAuditRepository;
        this.checkInAuditRepository = checkInAuditRepository;
        this.noShowAuditRepository = noShowAuditRepository;
        this.depositProcessRepository = depositProcessRepository;
        this.dispositionObligationRepository = dispositionObligationRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** current QR를 소비해 기존 FULFILLED 의미로 예약과 MenuHold를 원자 종결한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationVisitCommandResult checkIn(
            long operatorAccountId,
            long storeId,
            byte[] tokenDigest,
            IdempotencyCommand command,
            Instant requestedAt,
            String correlationId
    ) {
        requireCommand(
                operatorAccountId,
                storeId,
                command,
                "RESERVATION_QR_CHECK_IN",
                requestedAt,
                correlationId,
                "reservation-qr-check-in:store-operator:"
        );
        if (tokenDigest == null || tokenDigest.length != 32) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () ->
                checkInFresh(
                        operatorAccountId,
                        storeId,
                        tokenDigest,
                        requestedAt,
                        correlationId
                ));
        return visitResult(outcome);
    }

    /** 공통 +5분 경계 뒤 필수 후보 사유로 Reservation.NO_SHOW와 MenuHold.FORFEITED를 확정한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationVisitCommandResult markNoShow(
            long operatorAccountId,
            long storeId,
            long reservationId,
            ReservationNoShowReason reason,
            IdempotencyCommand command,
            Instant requestedAt,
            String correlationId
    ) {
        requireCommand(
                operatorAccountId,
                storeId,
                command,
                "RESERVATION_NO_SHOW",
                requestedAt,
                correlationId,
                "reservation-no-show:store-operator:"
        );
        if (reservationId <= 0 || reason == null) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () ->
                noShowFresh(
                        operatorAccountId,
                        storeId,
                        reservationId,
                        reason,
                        requestedAt,
                        correlationId
                ));
        return visitResult(outcome);
    }

    private BusinessResult<ReservationDetailResponse> checkInFresh(
            long operatorAccountId,
            long storeId,
            byte[] tokenDigest,
            Instant requestedAt,
            String correlationId
    ) {
        long reservationId = grantRepository.findReservationIdByTokenDigest(tokenDigest)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.CHECK_IN_QR_UNAVAILABLE
                ));
        Reservation reservation = lockedStoreReservation(reservationId, storeId);
        ReservationCheckInQrGrant grant = grantRepository
                .findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.CHECK_IN_QR_UNAVAILABLE
                ));
        Instant occurredAt = clock.instant();
        if (!grant.isUsable(tokenDigest, occurredAt)) {
            throw new ServiceException(ReservationErrorCode.CHECK_IN_QR_UNAVAILABLE);
        }
        epochService.requireCurrent(reservation.getConsumerAccountId(), grant.epochSnapshot());
        requireCheckInWindow(reservation, occurredAt);
        requireConfirmed(reservation);
        VisitDispositionPlan dispositionPlan = dispositionPlan(
                reservation,
                correlationId,
                "RESERVATION_FULFILLED",
                "CONSUMER",
                10_000
        );

        ReservationMenuHoldTerminationPresence presence = lockMenuHold(reservationId);
        reservation.fulfill(occurredAt);
        if (presence == ReservationMenuHoldTerminationPresence.HOLD_PRESENT) {
            requireMenuResult(
                    menuHoldPort.fulfill(reservationId, correlationId),
                    reservationId,
                    ReservationMenuHoldResult.Outcome.FULFILLED
            );
        }
        fulfillmentAuditRepository.saveAndFlush(ReservationFulfillmentAudit.recordSuccess(
                reservationId,
                ReservationFulfillmentActorType.STORE_OPERATOR,
                operatorAccountId,
                requestedAt,
                occurredAt,
                ReservationStatus.CONFIRMED,
                ReservationStatus.FULFILLED,
                reservation.getReservationTimePolicyVersion(),
                reservation.getCapacityPolicyVersion(),
                correlationId
        ));
        checkInAuditRepository.save(ReservationCheckInAudit.recordQrFulfilled(
                reservationId,
                storeId,
                operatorAccountId,
                grant.getTokenVersion(),
                requestedAt,
                occurredAt,
                correlationId
        ));
        grant.consume(occurredAt);
        ReservationDepositDispositionResponse depositDisposition =
                persistDispositionObligation(dispositionPlan, occurredAt);
        return success(reservation, presence, depositDisposition);
    }

    private BusinessResult<ReservationDetailResponse> noShowFresh(
            long operatorAccountId,
            long storeId,
            long reservationId,
            ReservationNoShowReason reason,
            Instant requestedAt,
            String correlationId
    ) {
        Reservation reservation = lockedStoreReservation(reservationId, storeId);
        Instant occurredAt = clock.instant();
        requireConfirmed(reservation);
        if (reservation.getStartAt() == null
                || occurredAt.isBefore(reservation.getStartAt().plusSeconds(
                        CHECK_IN_WINDOW_SECONDS
                ))) {
            throw new ServiceException(ReservationErrorCode.NO_SHOW_TOO_EARLY);
        }
        VisitDispositionPlan dispositionPlan = noShowDispositionPlan(
                reservation,
                reason,
                correlationId
        );
        ReservationMenuHoldTerminationPresence presence = lockMenuHold(reservationId);
        reservation.markNoShow(occurredAt);
        if (presence == ReservationMenuHoldTerminationPresence.HOLD_PRESENT) {
            requireMenuResult(
                    menuHoldPort.forfeit(reservationId, correlationId),
                    reservationId,
                    ReservationMenuHoldResult.Outcome.FORFEITED
            );
        }
        noShowAuditRepository.save(ReservationNoShowAudit.record(
                reservationId,
                storeId,
                operatorAccountId,
                reason,
                requestedAt,
                occurredAt,
                reservation.getReservationTimePolicyVersion(),
                reservation.getCapacityPolicyVersion(),
                correlationId
        ));
        ReservationDepositDispositionResponse depositDisposition =
                persistDispositionObligation(dispositionPlan, occurredAt);
        return success(reservation, presence, depositDisposition);
    }

    private VisitDispositionPlan noShowDispositionPlan(
            Reservation reservation,
            ReservationNoShowReason reason,
            String sourceEventId
    ) {
        if (!Long.valueOf(2L).equals(reservation.getCancellationPolicyVersion())) {
            return null;
        }
        ReservationDepositProcessRepository.DepositProcessLink link =
                requireCompletedDepositProcessLink(reservation);
        return switch (reason) {
            case USER_CAUSE_CANDIDATE -> dispositionPlan(
                    reservation,
                    link,
                    sourceEventId,
                    "RESERVATION_NO_SHOW",
                    "CONSUMER",
                    0
            );
            case STORE_CAUSE_CANDIDATE -> dispositionPlan(
                    reservation,
                    link,
                    sourceEventId,
                    "RESERVATION_NO_SHOW",
                    "STORE_RESPONSIBLE",
                    10_000
            );
            case PLATFORM_EXTERNAL_CAUSE_CANDIDATE -> dispositionPlan(
                    reservation,
                    link,
                    sourceEventId,
                    "RESERVATION_NO_SHOW",
                    "PLATFORM_RESPONSIBLE",
                    10_000
            );
            case UNCLEAR -> null;
        };
    }

    private VisitDispositionPlan dispositionPlan(
            Reservation reservation,
            String sourceEventId,
            String sourceEventType,
            String responsibilityCode,
            int targetRefundRateBasisPoints
    ) {
        if (!Long.valueOf(2L).equals(reservation.getCancellationPolicyVersion())) {
            return null;
        }
        return dispositionPlan(
                reservation,
                requireCompletedDepositProcessLink(reservation),
                sourceEventId,
                sourceEventType,
                responsibilityCode,
                targetRefundRateBasisPoints
        );
    }

    private VisitDispositionPlan dispositionPlan(
            Reservation reservation,
            ReservationDepositProcessRepository.DepositProcessLink link,
            String sourceEventId,
            String sourceEventType,
            String responsibilityCode,
            int targetRefundRateBasisPoints
    ) {
        if (dispositionObligationRepository == null) {
            throw new IllegalStateException(
                    "reservation deposit disposition dependencies are required");
        }
        return new VisitDispositionPlan(
                link.getProcessId(),
                reservation.getId(),
                link.getPaymentId(),
                sourceEventId,
                sourceEventType,
                responsibilityCode,
                targetRefundRateBasisPoints,
                UUID.randomUUID().toString()
        );
    }

    private ReservationDepositProcessRepository.DepositProcessLink
            requireCompletedDepositProcessLink(Reservation reservation) {
        if (depositProcessRepository == null) {
            throw new IllegalStateException(
                    "reservation deposit disposition dependencies are required");
        }
        ReservationDepositProcessRepository.DepositProcessLink link =
                depositProcessRepository
                        .findDepositProcessLinkByFinalReservationId(reservation.getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "completed reservation deposit process link is required"));
        if (link.getProcessId() <= 0
                || link.getStatus() != ReservationDepositProcessStatus.COMPLETED
                || link.getFinalReservationId() == null
                || !Objects.equals(link.getFinalReservationId(), reservation.getId())
                || link.getPaymentId() == null
                || !link.getPaymentId().matches("^[1-9][0-9]{0,18}$")) {
            throw new IllegalStateException(
                    "completed reservation deposit process link is required");
        }
        return link;
    }

    private ReservationDepositDispositionResponse persistDispositionObligation(
            VisitDispositionPlan plan,
            Instant now
    ) {
        if (plan == null) {
            return null;
        }
        ReservationDepositDispositionObligation obligation =
                ReservationDepositDispositionObligation.pending(
                        plan.processId(),
                        plan.reservationId(),
                        plan.paymentId(),
                        plan.sourceEventId(),
                        plan.sourceEventType(),
                        null,
                        2L,
                        plan.responsibilityCode(),
                        plan.targetRefundRateBasisPoints(),
                        plan.obligationKey(),
                        plan.obligationKey(),
                        now
                );
        return ReservationDepositDispositionResponse.from(
                dispositionObligationRepository.saveAndFlush(obligation));
    }

    private Reservation lockedStoreReservation(long reservationId, long storeId) {
        return reservationRepository.findByIdAndStoreIdForUpdate(reservationId, storeId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND
                ));
    }

    private ReservationMenuHoldTerminationPresence lockMenuHold(long reservationId) {
        ReservationMenuHoldTerminationPresence presence =
                menuHoldPort.lockForTermination(reservationId);
        if (presence == null) {
            throw new IllegalStateException("menu hold termination presence is required");
        }
        return presence;
    }

    private BusinessResult<ReservationDetailResponse> success(
            Reservation reservation,
            ReservationMenuHoldTerminationPresence presence,
            ReservationDepositDispositionResponse depositDisposition
    ) {
        List<ReservationMenuHoldItemSnapshot> snapshots =
                presence == ReservationMenuHoldTerminationPresence.HOLD_PRESENT
                        ? menuHoldPort.findSnapshots(reservation.getId())
                        : List.of();
        if (snapshots == null) {
            throw new IllegalStateException("menu hold snapshots are required");
        }
        return new BusinessResult<>(
                HttpStatus.OK.value(),
                SUCCESS,
                "RESERVATION",
                String.valueOf(reservation.getId()),
                ReservationDetailResponse.from(
                        reservation,
                        snapshots,
                        null,
                        null,
                        depositDisposition
                )
        );
    }

    private record VisitDispositionPlan(
            long processId,
            long reservationId,
            String paymentId,
            String sourceEventId,
            String sourceEventType,
            String responsibilityCode,
            int targetRefundRateBasisPoints,
            String obligationKey
    ) {
    }

    private ReservationVisitCommandResult visitResult(IdempotentOutcome outcome) {
        if (outcome == null
                || outcome.data() == null
                || outcome.httpStatus() != HttpStatus.OK.value()
                || !SUCCESS.equals(outcome.responseCode())
                || !"RESERVATION".equals(outcome.resourceType())) {
            throw new IllegalStateException("reservation visit outcome is inconsistent");
        }
        ReservationDetailResponse data = objectMapper.treeToValue(
                outcome.data(),
                ReservationDetailResponse.class
        );
        if (!outcome.resourceId().equals(data.reservationId())) {
            throw new IllegalStateException("reservation visit resource id is inconsistent");
        }
        return new ReservationVisitCommandResult(outcome.httpStatus(), data);
    }

    private static void requireConfirmed(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private static void requireCheckInWindow(Reservation reservation, Instant now) {
        Instant startAt = reservation.getStartAt();
        if (startAt == null
                || now.isBefore(startAt)
                || !now.isBefore(startAt.plusSeconds(CHECK_IN_WINDOW_SECONDS))) {
            throw new ServiceException(ReservationErrorCode.OUTSIDE_CHECK_IN_WINDOW);
        }
    }

    private static void requireMenuResult(
            ReservationMenuHoldResult result,
            long reservationId,
            ReservationMenuHoldResult.Outcome expected
    ) {
        if (result == null
                || result.reservationId() != reservationId
                || result.outcome() != expected) {
            throw new IllegalStateException("menu hold termination result is inconsistent");
        }
    }

    private static void requireCommand(
            long operatorAccountId,
            long storeId,
            IdempotencyCommand command,
            String commandType,
            Instant requestedAt,
            String correlationId,
            String correlationPrefix
    ) {
        String key = command == null ? null : command.idempotencyKey();
        String expectedCorrelation = key == null
                ? null
                : correlationPrefix + operatorAccountId + ":" + key;
        if (operatorAccountId <= 0
                || storeId <= 0
                || command == null
                || !"store-operator".equals(command.principalNamespace())
                || command.principalId() != operatorAccountId
                || !commandType.equals(command.commandType())
                || requestedAt == null
                || !java.util.Objects.equals(expectedCorrelation, correlationId)) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}
