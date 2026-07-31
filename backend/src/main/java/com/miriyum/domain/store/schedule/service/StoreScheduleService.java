package com.miriyum.domain.store.schedule.service;

import com.miriyum.domain.store.core.service.StoreScheduleAuthority;
import com.miriyum.domain.store.core.service.StoreScheduledActivationDecision;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.dto.OperatingHoursResponse;
import com.miriyum.domain.store.schedule.dto.ReservationTimeSlotsResponse;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationRequest;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationCancellationRequest;
import com.miriyum.domain.store.schedule.dto.WeeklyOperatingHoursRequest;
import com.miriyum.domain.store.schedule.dto.WeeklyReservationTimeSlotsRequest;
import com.miriyum.domain.store.schedule.entity.OperatingScheduleEntry;
import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.entity.StoreScheduleState;
import com.miriyum.domain.store.schedule.entity.StoreScheduleAuditEvent;
import com.miriyum.domain.store.schedule.model.ScheduleAuditAction;
import com.miriyum.domain.store.schedule.model.ScheduleAuditOutcome;
import com.miriyum.domain.store.schedule.model.ScheduleAuditRecord;
import com.miriyum.domain.store.schedule.model.ScheduleStream;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.store.schedule.model.PublicationMode;
import com.miriyum.domain.store.schedule.model.WeeklyInterval;
import com.miriyum.domain.store.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.ReservationScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.store.schedule.repository.StoreScheduleAuditEventRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class StoreScheduleService {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String RESOURCE_TYPE = "STORE_SCHEDULE";
    private static final String SUCCESS_RESPONSE_CODE = "SUCCESS";

    private final StoreService storeService;
    private final StoreScheduleStateRepository stateRepository;
    private final OperatingScheduleVersionRepository operatingRepository;
    private final ReservationScheduleVersionRepository reservationRepository;
    private final StoreScheduleAuditEventRepository auditRepository;
    private final WeeklySchedulePolicy schedulePolicy;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<OperatingHoursResponse> createOperatingDraft(
            long operatorId,
            long storeId,
            IdempotencyKey key,
            WeeklyOperatingHoursRequest request
    ) {
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "STORE_OPERATING_HOURS_DRAFT_CREATE",
                key.value(),
                StoreScheduleFingerprint.forOperating(storeId, request));

        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            StoreScheduleAuthority authority =
                    storeService.requireSchedulePublicationAuthority(
                            operatorId,
                            storeId);
            StoreScheduleState state = initializeAndLock(storeId);
            List<WeeklyInterval> intervals =
                    schedulePolicy.validateOperating(request);
            OperatingScheduleVersion version = operatingRepository.saveAndFlush(
                    OperatingScheduleVersion.createDraft(
                            storeId,
                            state.allocateOperatingVersion(),
                            authority.timeZoneId(),
                            intervals));
            Instant now = clock.instant();
            auditRepository.save(StoreScheduleAuditEvent.recordOperator(
                    storeId,
                    operatorId,
                    new ScheduleAuditRecord(
                            ScheduleStream.OPERATING,
                            version.getVersionNumber(),
                            null,
                            null,
                            ScheduleAuditAction.DRAFT_CREATED,
                            null,
                            ScheduleVersionStatus.DRAFT,
                            authority.timeZoneId(),
                            now,
                            null,
                            now,
                            null,
                            key.value(),
                            ScheduleAuditOutcome.SUCCEEDED)));
            OperatingHoursResponse response = OperatingHoursResponse.from(version);
            return success(
                    resourceId(storeId, "OPERATING", response.version()),
                    response);
        });
        return commandResult(outcome, OperatingHoursResponse.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<OperatingHoursResponse> replaceOperatingHours(
            long operatorId,
            long storeId,
            IdempotencyKey key,
            WeeklyOperatingHoursRequest request
    ) {
        return createOperatingDraft(operatorId, storeId, key, request);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<OperatingHoursResponse> publishOperating(
            long operatorId,
            long storeId,
            long versionNumber,
            IdempotencyKey key,
            SchedulePublicationRequest request
    ) {
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "STORE_OPERATING_HOURS_PUBLISH",
                key.value(),
                StoreScheduleFingerprint.forOperatingPublication(
                        storeId,
                        versionNumber,
                        request));

        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            StoreScheduleAuthority authority =
                    storeService.requireSchedulePublicationAuthority(
                            operatorId,
                            storeId);
            StoreScheduleState state = initializeAndLock(storeId);
            OperatingScheduleVersion target = operatingRepository
                    .findByStoreIdAndVersionNumber(storeId, versionNumber)
                    .orElseThrow(() -> new ServiceException(
                            StoreErrorCode.SCHEDULE_CONFLICT));
            Instant now = clock.instant();
            validatePublicationRequest(request, now);
            if (request.publicationMode() == PublicationMode.SCHEDULED) {
                Instant effectiveAt = request.effectiveAt().toInstant();
                target.schedule(effectiveAt, request.changeReason());
                auditRepository.save(StoreScheduleAuditEvent.recordOperator(
                        storeId,
                        operatorId,
                        new ScheduleAuditRecord(
                                ScheduleStream.OPERATING,
                                target.getVersionNumber(),
                                activeVersionNumber(state),
                                activeVersionNumber(state),
                                ScheduleAuditAction.PUBLICATION_SCHEDULED,
                                ScheduleVersionStatus.DRAFT,
                                ScheduleVersionStatus.SCHEDULED,
                                authority.timeZoneId(),
                                now,
                                effectiveAt,
                                now,
                                request.changeReason(),
                                key.value(),
                                ScheduleAuditOutcome.SUCCEEDED)));
                OperatingHoursResponse response =
                        OperatingHoursResponse.from(target);
                return success(
                        resourceId(storeId, "OPERATING", response.version()),
                        response);
            }

            Long previousActiveVersion = null;
            Long activeVersionId = state.getActiveOperatingScheduleVersionId();
            if (activeVersionId != null) {
                OperatingScheduleVersion previous = operatingRepository
                        .findById(activeVersionId)
                        .orElseThrow(() -> new ServiceException(
                                StoreErrorCode.SCHEDULE_CONFLICT));
                previousActiveVersion = previous.getVersionNumber();
                previous.retire();
            }

            target.activate(now, request.changeReason());
            ReservationScheduleVersion retiredReservation =
                    retireActiveReservation(state);
            state.activateOperating(target.getId());
            if (retiredReservation != null) {
                auditRepository.save(StoreScheduleAuditEvent.recordOperator(
                        storeId,
                        operatorId,
                        reservationRetirementRecord(
                                retiredReservation,
                                authority.timeZoneId(),
                                now,
                                now,
                                now,
                                request.changeReason(),
                                key.value())));
            }
            auditRepository.save(StoreScheduleAuditEvent.recordOperator(
                    storeId,
                    operatorId,
                    new ScheduleAuditRecord(
                            ScheduleStream.OPERATING,
                            target.getVersionNumber(),
                            previousActiveVersion,
                            target.getVersionNumber(),
                            ScheduleAuditAction.IMMEDIATE_PUBLISHED,
                            ScheduleVersionStatus.DRAFT,
                            ScheduleVersionStatus.ACTIVE,
                            authority.timeZoneId(),
                            now,
                            now,
                            now,
                            request.changeReason(),
                            key.value(),
                            ScheduleAuditOutcome.SUCCEEDED)));
            OperatingHoursResponse response = OperatingHoursResponse.from(target);
            return success(
                    resourceId(storeId, "OPERATING", response.version()),
                    response);
        });
        return commandResult(outcome, OperatingHoursResponse.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<OperatingHoursResponse>
            cancelOperatingPublication(
                    long operatorId,
                    long storeId,
                    long versionNumber,
                    IdempotencyKey key,
                    SchedulePublicationCancellationRequest request
            ) {
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "STORE_OPERATING_HOURS_PUBLICATION_CANCEL",
                key.value(),
                StoreScheduleFingerprint.forCancellation(
                        "operating-hours",
                        storeId,
                        versionNumber,
                        request));
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            StoreScheduleAuthority authority =
                    storeService.requireSchedulePublicationAuthority(
                            operatorId,
                            storeId);
            StoreScheduleState state = initializeAndLock(storeId);
            OperatingScheduleVersion target = operatingRepository
                    .findByStoreIdAndVersionNumber(storeId, versionNumber)
                    .orElseThrow(() -> new ServiceException(
                            StoreErrorCode.SCHEDULE_CONFLICT));
            Instant scheduledAt = target.getEffectiveAt();
            Instant now = clock.instant();
            requireCancellationBeforeEffectiveTime(scheduledAt, now);
            target.cancelPublication();
            auditRepository.save(StoreScheduleAuditEvent.recordOperator(
                    storeId,
                    operatorId,
                    new ScheduleAuditRecord(
                            ScheduleStream.OPERATING,
                            target.getVersionNumber(),
                            activeVersionNumber(state),
                            activeVersionNumber(state),
                            ScheduleAuditAction.SCHEDULED_PUBLICATION_CANCELLED,
                            ScheduleVersionStatus.SCHEDULED,
                            ScheduleVersionStatus.DRAFT,
                            authority.timeZoneId(),
                            now,
                            scheduledAt,
                            now,
                            request.changeReason(),
                            key.value(),
                            ScheduleAuditOutcome.SUCCEEDED)));
            OperatingHoursResponse response = OperatingHoursResponse.from(target);
            return success(
                    resourceId(storeId, "OPERATING", response.version()),
                    response);
        });
        return commandResult(outcome, OperatingHoursResponse.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<ReservationTimeSlotsResponse>
            createReservationDraft(
                    long operatorId,
                    long storeId,
                    IdempotencyKey key,
                    WeeklyReservationTimeSlotsRequest request
            ) {
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "STORE_RESERVATION_TIME_SLOTS_DRAFT_CREATE",
                key.value(),
                StoreScheduleFingerprint.forReservation(storeId, request));

        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            StoreScheduleAuthority authority =
                    storeService.requireSchedulePublicationAuthority(
                            operatorId,
                            storeId);
            StoreScheduleState state = initializeAndLock(storeId);
            Long operatingVersionId =
                    state.getActiveOperatingScheduleVersionId();
            if (operatingVersionId == null) {
                throw new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT);
            }
            OperatingScheduleVersion operating =
                    operatingRepository.findById(operatingVersionId)
                            .orElseThrow(() -> new ServiceException(
                                    StoreErrorCode.SCHEDULE_CONFLICT));
            List<WeeklyInterval> operatingIntervals = operating.getEntries().stream()
                    .map(OperatingScheduleEntry::toWeeklyInterval)
                    .toList();
            List<WeeklyInterval> reservationIntervals =
                    schedulePolicy.validateReservation(request, operatingIntervals);
            ReservationScheduleVersion version =
                    reservationRepository.saveAndFlush(
                            ReservationScheduleVersion.createDraft(
                                    storeId,
                                    state.allocateReservationVersion(),
                                    operatingVersionId,
                                    authority.timeZoneId(),
                                    reservationIntervals));
            Instant now = clock.instant();
            auditRepository.save(StoreScheduleAuditEvent.recordOperator(
                    storeId,
                    operatorId,
                    new ScheduleAuditRecord(
                            ScheduleStream.RESERVATION,
                            version.getVersionNumber(),
                            null,
                            null,
                            ScheduleAuditAction.DRAFT_CREATED,
                            null,
                            ScheduleVersionStatus.DRAFT,
                            authority.timeZoneId(),
                            now,
                            null,
                            now,
                            null,
                            key.value(),
                            ScheduleAuditOutcome.SUCCEEDED)));
            ReservationTimeSlotsResponse response =
                    ReservationTimeSlotsResponse.from(version);
            return success(
                    resourceId(storeId, "RESERVATION", response.version()),
                    response);
        });
        return commandResult(outcome, ReservationTimeSlotsResponse.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<ReservationTimeSlotsResponse>
            replaceReservationTimeSlots(
                    long operatorId,
                    long storeId,
                    IdempotencyKey key,
                    WeeklyReservationTimeSlotsRequest request
            ) {
        return createReservationDraft(operatorId, storeId, key, request);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<ReservationTimeSlotsResponse>
            publishReservation(
                    long operatorId,
                    long storeId,
                    long versionNumber,
                    IdempotencyKey key,
                    SchedulePublicationRequest request
            ) {
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "STORE_RESERVATION_TIME_SLOTS_PUBLISH",
                key.value(),
                StoreScheduleFingerprint.forReservationPublication(
                        storeId,
                        versionNumber,
                        request));
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            StoreScheduleAuthority authority =
                    storeService.requireSchedulePublicationAuthority(
                            operatorId,
                            storeId);
            StoreScheduleState state = initializeAndLock(storeId);
            ReservationScheduleVersion target = reservationRepository
                    .findByStoreIdAndVersionNumber(storeId, versionNumber)
                    .orElseThrow(() -> new ServiceException(
                            StoreErrorCode.SCHEDULE_CONFLICT));
            if (!target.getValidatedOperatingVersionId().equals(
                    state.getActiveOperatingScheduleVersionId())) {
                throw new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT);
            }
            Instant now = clock.instant();
            validatePublicationRequest(request, now);
            Long previousVersion = activeReservationVersionNumber(state);
            if (request.publicationMode() == PublicationMode.SCHEDULED) {
                Instant effectiveAt = request.effectiveAt().toInstant();
                target.schedule(effectiveAt, request.changeReason());
                savePublicationAudit(
                        storeId, operatorId, key, authority,
                        target.getVersionNumber(), previousVersion, previousVersion,
                        ScheduleStream.RESERVATION,
                        ScheduleAuditAction.PUBLICATION_SCHEDULED,
                        ScheduleVersionStatus.DRAFT,
                        ScheduleVersionStatus.SCHEDULED,
                        now, effectiveAt, request.changeReason());
            } else {
                Long activeId = state.getActiveReservationScheduleVersionId();
                if (activeId != null) {
                    reservationRepository.findById(activeId)
                            .orElseThrow(() -> new ServiceException(
                                    StoreErrorCode.SCHEDULE_CONFLICT))
                            .retire();
                }
                target.activate(now, request.changeReason());
                state.activateReservation(target.getId());
                savePublicationAudit(
                        storeId, operatorId, key, authority,
                        target.getVersionNumber(), previousVersion,
                        target.getVersionNumber(),
                        ScheduleStream.RESERVATION,
                        ScheduleAuditAction.IMMEDIATE_PUBLISHED,
                        ScheduleVersionStatus.DRAFT,
                        ScheduleVersionStatus.ACTIVE,
                        now, now, request.changeReason());
            }
            ReservationTimeSlotsResponse response =
                    ReservationTimeSlotsResponse.from(target);
            return success(
                    resourceId(storeId, "RESERVATION", response.version()),
                    response);
        });
        return commandResult(outcome, ReservationTimeSlotsResponse.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<ReservationTimeSlotsResponse>
            cancelReservationPublication(
                    long operatorId,
                    long storeId,
                    long versionNumber,
                    IdempotencyKey key,
                    SchedulePublicationCancellationRequest request
            ) {
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "STORE_RESERVATION_TIME_SLOTS_PUBLICATION_CANCEL",
                key.value(),
                StoreScheduleFingerprint.forCancellation(
                        "reservation-time-slots",
                        storeId,
                        versionNumber,
                        request));
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            StoreScheduleAuthority authority =
                    storeService.requireSchedulePublicationAuthority(
                            operatorId,
                            storeId);
            StoreScheduleState state = initializeAndLock(storeId);
            ReservationScheduleVersion target = reservationRepository
                    .findByStoreIdAndVersionNumber(storeId, versionNumber)
                    .orElseThrow(() -> new ServiceException(
                            StoreErrorCode.SCHEDULE_CONFLICT));
            Instant scheduledAt = target.getEffectiveAt();
            Instant now = clock.instant();
            requireCancellationBeforeEffectiveTime(scheduledAt, now);
            target.cancelPublication();
            Long activeVersion = activeReservationVersionNumber(state);
            savePublicationAudit(
                    storeId, operatorId, key, authority,
                    target.getVersionNumber(), activeVersion, activeVersion,
                    ScheduleStream.RESERVATION,
                    ScheduleAuditAction.SCHEDULED_PUBLICATION_CANCELLED,
                    ScheduleVersionStatus.SCHEDULED,
                    ScheduleVersionStatus.DRAFT,
                    now, scheduledAt, request.changeReason());
            ReservationTimeSlotsResponse response =
                    ReservationTimeSlotsResponse.from(target);
            return success(
                    resourceId(storeId, "RESERVATION", response.version()),
                    response);
        });
        return commandResult(outcome, ReservationTimeSlotsResponse.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void activateDueOperating(long versionId) {
        Long storeId = operatingRepository.findStoreIdById(versionId)
                .orElse(null);
        if (storeId == null) {
            return;
        }
        StoreScheduledActivationDecision decision =
                storeService.inspectScheduledActivation(storeId);
        StoreScheduleState state = initializeAndLock(storeId);
        OperatingScheduleVersion target =
                operatingRepository.findForUpdateById(versionId).orElse(null);
        Instant now = clock.instant();
        if (target == null
                || target.getStatus() != ScheduleVersionStatus.SCHEDULED
                || target.getEffectiveAt().isAfter(now)) {
            return;
        }
        OperatingScheduleVersion earliest = operatingRepository
                .findFirstByStoreIdAndStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        storeId,
                        ScheduleVersionStatus.SCHEDULED,
                        now)
                .orElse(null);
        if (earliest == null || !earliest.getId().equals(versionId)) {
            return;
        }
        if (!decision.activationAllowed()) {
            Instant effectiveAt = target.getEffectiveAt();
            target.failActivation();
            auditRepository.save(StoreScheduleAuditEvent.recordSystem(
                    storeId,
                    new ScheduleAuditRecord(
                            ScheduleStream.OPERATING,
                            target.getVersionNumber(),
                            activeVersionNumber(state),
                            activeVersionNumber(state),
                            ScheduleAuditAction.SCHEDULE_ACTIVATION_FAILED,
                            ScheduleVersionStatus.SCHEDULED,
                            ScheduleVersionStatus.ACTIVATION_FAILED,
                            decision.timeZoneId(),
                            effectiveAt,
                            effectiveAt,
                            now,
                            target.getChangeReason(),
                            "scheduled-operating-" + versionId,
                            ScheduleAuditOutcome.FAILED)));
            return;
        }
        Long previousVersion = null;
        Long activeId = state.getActiveOperatingScheduleVersionId();
        if (activeId != null) {
            OperatingScheduleVersion previous = operatingRepository
                    .findById(activeId)
                    .orElseThrow(() -> new ServiceException(
                            StoreErrorCode.SCHEDULE_CONFLICT));
            previousVersion = previous.getVersionNumber();
            previous.retire();
        }
        Instant effectiveAt = target.getEffectiveAt();
        target.activate(now, target.getChangeReason());
        ReservationScheduleVersion retiredReservation =
                retireActiveReservation(state);
        state.activateOperating(target.getId());
        if (retiredReservation != null) {
            auditRepository.save(StoreScheduleAuditEvent.recordSystem(
                    storeId,
                    reservationRetirementRecord(
                            retiredReservation,
                            decision.timeZoneId(),
                            effectiveAt,
                            effectiveAt,
                            now,
                            target.getChangeReason(),
                            "scheduled-operating-" + versionId)));
        }
        auditRepository.save(StoreScheduleAuditEvent.recordSystem(
                storeId,
                new ScheduleAuditRecord(
                        ScheduleStream.OPERATING,
                        target.getVersionNumber(),
                        previousVersion,
                        target.getVersionNumber(),
                        ScheduleAuditAction.SCHEDULE_ACTIVATED,
                        ScheduleVersionStatus.SCHEDULED,
                        ScheduleVersionStatus.ACTIVE,
                        decision.timeZoneId(),
                        effectiveAt,
                        effectiveAt,
                        now,
                        target.getChangeReason(),
                        "scheduled-operating-" + versionId,
                        ScheduleAuditOutcome.SUCCEEDED)));
    }

    private static void requireCancellationBeforeEffectiveTime(
            Instant effectiveAt,
            Instant now
    ) {
        if (effectiveAt == null || !effectiveAt.isAfter(now)) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void activateDueReservation(long versionId) {
        Long storeId = reservationRepository.findStoreIdById(versionId)
                .orElse(null);
        if (storeId == null) {
            return;
        }
        StoreScheduledActivationDecision decision =
                storeService.inspectScheduledActivation(storeId);
        StoreScheduleState state = initializeAndLock(storeId);
        ReservationScheduleVersion target =
                reservationRepository.findForUpdateById(versionId).orElse(null);
        Instant now = clock.instant();
        if (target == null
                || target.getStatus() != ScheduleVersionStatus.SCHEDULED
                || target.getEffectiveAt().isAfter(now)) {
            return;
        }
        ReservationScheduleVersion earliest = reservationRepository
                .findFirstByStoreIdAndStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        storeId,
                        ScheduleVersionStatus.SCHEDULED,
                        now)
                .orElse(null);
        if (earliest == null || !earliest.getId().equals(versionId)) {
            return;
        }
        Instant effectiveAt = target.getEffectiveAt();
        if (!decision.activationAllowed()) {
            target.failActivation();
            auditRepository.save(StoreScheduleAuditEvent.recordSystem(
                    storeId,
                    new ScheduleAuditRecord(
                            ScheduleStream.RESERVATION,
                            target.getVersionNumber(),
                            activeReservationVersionNumber(state),
                            activeReservationVersionNumber(state),
                            ScheduleAuditAction.SCHEDULE_ACTIVATION_FAILED,
                            ScheduleVersionStatus.SCHEDULED,
                            ScheduleVersionStatus.ACTIVATION_FAILED,
                            decision.timeZoneId(),
                            effectiveAt,
                            effectiveAt,
                            now,
                            target.getChangeReason(),
                            "scheduled-reservation-" + versionId,
                            ScheduleAuditOutcome.FAILED)));
            return;
        }
        if (!target.getValidatedOperatingVersionId().equals(
                state.getActiveOperatingScheduleVersionId())) {
            target.failActivation();
            auditRepository.save(StoreScheduleAuditEvent.recordSystem(
                    storeId,
                    new ScheduleAuditRecord(
                            ScheduleStream.RESERVATION,
                            target.getVersionNumber(),
                            activeReservationVersionNumber(state),
                            activeReservationVersionNumber(state),
                            ScheduleAuditAction.SCHEDULE_ACTIVATION_FAILED,
                            ScheduleVersionStatus.SCHEDULED,
                            ScheduleVersionStatus.ACTIVATION_FAILED,
                            decision.timeZoneId(),
                            effectiveAt,
                            effectiveAt,
                            now,
                            target.getChangeReason(),
                            "scheduled-reservation-" + versionId,
                            ScheduleAuditOutcome.FAILED)));
            return;
        }
        Long previousVersion = null;
        Long activeId = state.getActiveReservationScheduleVersionId();
        if (activeId != null) {
            ReservationScheduleVersion previous = reservationRepository
                    .findById(activeId)
                    .orElseThrow(() -> new ServiceException(
                            StoreErrorCode.SCHEDULE_CONFLICT));
            previousVersion = previous.getVersionNumber();
            previous.retire();
        }
        target.activate(now, target.getChangeReason());
        state.activateReservation(target.getId());
        auditRepository.save(StoreScheduleAuditEvent.recordSystem(
                storeId,
                new ScheduleAuditRecord(
                        ScheduleStream.RESERVATION,
                        target.getVersionNumber(),
                        previousVersion,
                        target.getVersionNumber(),
                        ScheduleAuditAction.SCHEDULE_ACTIVATED,
                        ScheduleVersionStatus.SCHEDULED,
                        ScheduleVersionStatus.ACTIVE,
                        decision.timeZoneId(),
                        effectiveAt,
                        effectiveAt,
                        now,
                        target.getChangeReason(),
                        "scheduled-reservation-" + versionId,
                        ScheduleAuditOutcome.SUCCEEDED)));
    }

    private void validatePublicationRequest(
            SchedulePublicationRequest request,
            Instant now
    ) {
        boolean invalidImmediate = request.publicationMode() == PublicationMode.IMMEDIATE
                && request.effectiveAt() != null;
        boolean invalidScheduled = request.publicationMode() == PublicationMode.SCHEDULED
                && (request.effectiveAt() == null
                    || !request.effectiveAt().toInstant().isAfter(now));
        if (request.publicationMode() == null
                || invalidImmediate
                || invalidScheduled) {
            throw new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT);
        }
    }

    private Long activeVersionNumber(StoreScheduleState state) {
        Long id = state.getActiveOperatingScheduleVersionId();
        return id == null
                ? null
                : operatingRepository.findById(id)
                        .map(OperatingScheduleVersion::getVersionNumber)
                        .orElseThrow(() -> new ServiceException(
                                StoreErrorCode.SCHEDULE_CONFLICT));
    }

    private Long activeReservationVersionNumber(StoreScheduleState state) {
        Long id = state.getActiveReservationScheduleVersionId();
        return id == null
                ? null
                : reservationRepository.findById(id)
                        .map(ReservationScheduleVersion::getVersionNumber)
                        .orElseThrow(() -> new ServiceException(
                                StoreErrorCode.SCHEDULE_CONFLICT));
    }

    private ReservationScheduleVersion retireActiveReservation(
            StoreScheduleState state
    ) {
        Long reservationId = state.getActiveReservationScheduleVersionId();
        if (reservationId == null) {
            return null;
        }
        ReservationScheduleVersion reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new ServiceException(
                        StoreErrorCode.SCHEDULE_CONFLICT));
        reservation.retire();
        return reservation;
    }

    private ScheduleAuditRecord reservationRetirementRecord(
            ReservationScheduleVersion reservation,
            String timeZoneId,
            Instant requestedAt,
            Instant effectiveAt,
            Instant occurredAt,
            String changeReason,
            String requestId
    ) {
        return new ScheduleAuditRecord(
                ScheduleStream.RESERVATION,
                reservation.getVersionNumber(),
                reservation.getVersionNumber(),
                null,
                ScheduleAuditAction
                        .RESERVATION_RETIRED_BY_OPERATING_CHANGE,
                ScheduleVersionStatus.ACTIVE,
                ScheduleVersionStatus.RETIRED,
                timeZoneId,
                requestedAt,
                effectiveAt,
                occurredAt,
                changeReason,
                requestId,
                ScheduleAuditOutcome.SUCCEEDED);
    }

    private void savePublicationAudit(
            long storeId,
            long operatorId,
            IdempotencyKey key,
            StoreScheduleAuthority authority,
            long targetVersion,
            Long previousActiveVersion,
            Long newActiveVersion,
            ScheduleStream stream,
            ScheduleAuditAction action,
            ScheduleVersionStatus previousStatus,
            ScheduleVersionStatus newStatus,
            Instant requestedAt,
            Instant effectiveAt,
            String changeReason
    ) {
        auditRepository.save(StoreScheduleAuditEvent.recordOperator(
                storeId,
                operatorId,
                new ScheduleAuditRecord(
                        stream,
                        targetVersion,
                        previousActiveVersion,
                        newActiveVersion,
                        action,
                        previousStatus,
                        newStatus,
                        authority.timeZoneId(),
                        requestedAt,
                        effectiveAt,
                        requestedAt,
                        changeReason,
                        key.value(),
                        ScheduleAuditOutcome.SUCCEEDED)));
    }

    private StoreScheduleState initializeAndLock(long storeId) {
        stateRepository.initialize(storeId);
        return stateRepository.findForUpdateByStoreId(storeId)
                .orElseThrow(() ->
                        new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT));
    }

    private <T> BusinessResult<T> success(String resourceId, T data) {
        return new BusinessResult<>(
                HttpStatus.OK.value(),
                SUCCESS_RESPONSE_CODE,
                RESOURCE_TYPE,
                resourceId,
                data);
    }

    private <T> ScheduleCommandResult<T> commandResult(
            IdempotentOutcome outcome,
            Class<T> responseType
    ) {
        T response = objectMapper.treeToValue(outcome.data(), responseType);
        return new ScheduleCommandResult<>(outcome.httpStatus(), response);
    }

    private String resourceId(
            long storeId,
            String stream,
            long version
    ) {
        return storeId + ":" + stream + ":" + version;
    }
}
