package com.miriyum.domain.store.closure.service;

import com.miriyum.domain.store.closure.dto.RegularClosureDraftRequest;
import com.miriyum.domain.store.closure.dto.RegularClosureResponse;
import com.miriyum.domain.store.closure.entity.RegularClosureVersion;
import com.miriyum.domain.store.closure.entity.StoreClosureAuditEvent;
import com.miriyum.domain.store.closure.repository.RegularClosureVersionRepository;
import com.miriyum.domain.store.closure.repository.StoreClosureAuditEventRepository;
import com.miriyum.domain.store.closure.repository.TemporaryClosureRepository;
import com.miriyum.domain.store.closure.model.StoreClosureActorType;
import com.miriyum.domain.store.service.StoreScheduleAuthority;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationCancellationRequest;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationRequest;
import com.miriyum.domain.store.schedule.entity.StoreScheduleState;
import com.miriyum.domain.store.schedule.model.PublicationMode;
import com.miriyum.domain.store.schedule.model.ScheduleAuditOutcome;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.store.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.store.schedule.service.ScheduleCommandResult;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class StoreClosureService {
    private static final String PRINCIPAL = "store-operator";
    private static final String RESOURCE = "STORE_CLOSURE";
    private final StoreService storeService;
    private final StoreScheduleStateRepository stateRepository;
    private final RegularClosureVersionRepository regularRepository;
    private final TemporaryClosureRepository temporaryRepository;
    private final StoreClosureAuditEventRepository auditRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<RegularClosureResponse> createRegularDraft(
            long operatorId, long storeId, IdempotencyKey key, RegularClosureDraftRequest request) {
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(
                command(operatorId, "STORE_REGULAR_CLOSURE_DRAFT_CREATE", key,
                        StoreClosureFingerprint.regularDraft(storeId, request)), () -> {
                    StoreScheduleAuthority authority = storeService.requireSchedulePublicationAuthority(operatorId, storeId);
                    StoreScheduleState state = initializeAndLock(storeId);
                    Instant requestedAt = clock.instant();
                    RegularClosureVersion target = regularRepository.saveAndFlush(
                            RegularClosureVersion.createDraft(storeId, state.allocateRegularClosureVersion(),
                                    authority.timeZoneId(), request.weeklyDays(), request.dates()));
                    audit(target, operatorId, null, null, "DRAFT_CREATED", null, "DRAFT",
                            authority.timeZoneId(), requestedAt, null, null, key.value(),
                            ScheduleAuditOutcome.SUCCEEDED);
                    return success(target);
                });
        return result(outcome, RegularClosureResponse.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<RegularClosureResponse> publishRegular(
            long operatorId, long storeId, long version, IdempotencyKey key, SchedulePublicationRequest request) {
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(
                command(operatorId, "STORE_REGULAR_CLOSURE_PUBLICATION", key,
                        StoreClosureFingerprint.regularPublication(storeId, version, request)), () -> {
                    StoreScheduleAuthority authority = storeService.requireSchedulePublicationAuthority(operatorId, storeId);
                    StoreScheduleState state = initializeAndLock(storeId);
                    RegularClosureVersion target = regularRepository.findByStoreIdAndVersionNumber(storeId, version)
                            .orElseThrow(this::conflict);
                    Instant now = clock.instant();
                    validatePublication(request, now);
                    if (request.publicationMode() == PublicationMode.SCHEDULED) {
                        Instant effectiveAt = request.effectiveAt().toInstant();
                        if (regularRepository.existsByStoreIdAndEffectiveAt(storeId, effectiveAt)) throw conflict();
                        requireNoTemporaryConflict(target, effectiveAt);
                        Long activeVersion = activeRegularClosureVersionNumber(state);
                        target.schedule(effectiveAt, request.changeReason());
                        audit(target, operatorId, activeVersion, activeVersion,
                                "PUBLICATION_SCHEDULED", "DRAFT", "SCHEDULED",
                                authority.timeZoneId(), now, target.getEffectiveAt(),
                                request.changeReason(), key.value(), ScheduleAuditOutcome.SUCCEEDED);
                    } else {
                        requireNoTemporaryConflict(target, now);
                        Long previousActiveVersion = retireActive(state);
                        target.activate(now, request.changeReason());
                        state.activateRegularClosure(target.getId());
                        audit(target, operatorId, previousActiveVersion, target.getVersionNumber(),
                                "ACTIVATED", "DRAFT", "ACTIVE", authority.timeZoneId(),
                                now, now, request.changeReason(), key.value(),
                                ScheduleAuditOutcome.SUCCEEDED);
                    }
                    return success(target);
                });
        return result(outcome, RegularClosureResponse.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<RegularClosureResponse> cancelRegularPublication(
            long operatorId, long storeId, long version, IdempotencyKey key,
            SchedulePublicationCancellationRequest request) {
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(
                command(operatorId, "STORE_REGULAR_CLOSURE_PUBLICATION_CANCEL", key,
                        StoreClosureFingerprint.regularCancellation(storeId, version, request)), () -> {
                    StoreScheduleAuthority authority = storeService.requireSchedulePublicationAuthority(operatorId, storeId);
                    StoreScheduleState state = initializeAndLock(storeId);
                    RegularClosureVersion target = regularRepository.findByStoreIdAndVersionNumber(storeId, version)
                            .orElseThrow(this::conflict);
                    Instant requestedAt = clock.instant();
                    Instant effectiveAt = target.getEffectiveAt();
                    if (effectiveAt == null || !effectiveAt.isAfter(requestedAt)) throw conflict();
                    Long activeVersion = activeRegularClosureVersionNumber(state);
                    target.cancelPublication();
                    audit(target, operatorId, activeVersion, activeVersion,
                            "PUBLICATION_CANCELLED", "SCHEDULED", "DRAFT",
                            authority.timeZoneId(), requestedAt, effectiveAt,
                            request.changeReason(), key.value(), ScheduleAuditOutcome.SUCCEEDED);
                    return success(target);
                });
        return result(outcome, RegularClosureResponse.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void activateDueRegular(long versionId) {
        Long storeId = regularRepository.findStoreIdById(versionId).orElse(null);
        if (storeId == null) return;
        StoreScheduleAuthority authority;
        try {
            var decision = storeService.inspectScheduledActivation(storeId);
            authority = new StoreScheduleAuthority(storeId, decision.timeZoneId());
            StoreScheduleState state = initializeAndLock(storeId);
            RegularClosureVersion target = regularRepository.findForUpdateById(versionId).orElse(null);
            Instant now = clock.instant();
            if (target == null || target.getStatus() != ScheduleVersionStatus.SCHEDULED
                    || target.getEffectiveAt().isAfter(now)) return;
            RegularClosureVersion earliest = regularRepository
                    .findFirstByStoreIdAndStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                            storeId, ScheduleVersionStatus.SCHEDULED, now).orElse(null);
            if (earliest == null || !earliest.getId().equals(versionId)) return;
            if (!decision.activationAllowed()) {
                target.failActivation();
                Long activeVersion = activeRegularClosureVersionNumber(state);
                audit(target, null, activeVersion, activeVersion,
                        "ACTIVATION_FAILED", "SCHEDULED", "ACTIVATION_FAILED",
                        authority.timeZoneId(), target.getEffectiveAt(), target.getEffectiveAt(),
                        target.getChangeReason(), "scheduled-regular-" + versionId,
                        ScheduleAuditOutcome.FAILED);
                return;
            }
            if (hasTemporaryConflict(target, now)) {
                target.failActivation();
                Long activeVersion = activeRegularClosureVersionNumber(state);
                audit(target, null, activeVersion, activeVersion,
                        "ACTIVATION_FAILED", "SCHEDULED", "ACTIVATION_FAILED",
                        authority.timeZoneId(), target.getEffectiveAt(), target.getEffectiveAt(),
                        target.getChangeReason(), "scheduled-regular-" + versionId,
                        ScheduleAuditOutcome.FAILED);
                return;
            }
            Long previousActiveVersion = retireActive(state);
            target.activate(now, target.getChangeReason());
            state.activateRegularClosure(target.getId());
            audit(target, null, previousActiveVersion, target.getVersionNumber(),
                    "ACTIVATED", "SCHEDULED", "ACTIVE", authority.timeZoneId(),
                    target.getEffectiveAt(), target.getEffectiveAt(), target.getChangeReason(),
                    "scheduled-regular-" + versionId, ScheduleAuditOutcome.SUCCEEDED);
        } catch (ServiceException ignored) {
            // The owning Store is not activatable. The surrounding transaction rolls back.
        }
    }

    private Long retireActive(StoreScheduleState state) {
        Long activeId = state.getActiveRegularClosureVersionId();
        if (activeId == null) return null;
        RegularClosureVersion active = regularRepository.findById(activeId).orElseThrow(this::conflict);
        active.retire();
        return active.getVersionNumber();
    }

    private Long activeRegularClosureVersionNumber(StoreScheduleState state) {
        Long activeId = state.getActiveRegularClosureVersionId();
        return activeId == null ? null : regularRepository.findById(activeId)
                .map(RegularClosureVersion::getVersionNumber)
                .orElseThrow(this::conflict);
    }

    private void requireNoTemporaryConflict(RegularClosureVersion target, Instant effectiveFrom) {
        if (hasTemporaryConflict(target, effectiveFrom)) throw conflict();
    }

    private boolean hasTemporaryConflict(RegularClosureVersion target, Instant effectiveFrom) {
        ZoneId zone = ZoneId.of(target.getTimeZoneId());
        return temporaryRepository.findNonCancelledEndingAfter(target.getStoreId(), effectiveFrom)
                .stream().anyMatch(closure -> {
                    Instant relevantStart = closure.getStartAt().isBefore(effectiveFrom)
                            ? effectiveFrom : closure.getStartAt();
                    LocalDate first = relevantStart.atZone(zone).toLocalDate();
                    LocalDate last = closure.getEndAt().minusNanos(1).atZone(zone).toLocalDate();
                    return !first.isAfter(last)
                            && first.datesUntil(last.plusDays(1)).anyMatch(target::isClosedOn);
                });
    }

    private StoreScheduleState initializeAndLock(long storeId) {
        stateRepository.initialize(storeId);
        return stateRepository.findForUpdateByStoreId(storeId).orElseThrow(this::conflict);
    }

    private void validatePublication(SchedulePublicationRequest request, Instant now) {
        if (request.publicationMode() == null
                || (request.publicationMode() == PublicationMode.IMMEDIATE && request.effectiveAt() != null)
                || (request.publicationMode() == PublicationMode.SCHEDULED
                && (request.effectiveAt() == null || !request.effectiveAt().toInstant().isAfter(now)))) throw conflict();
    }

    private IdempotencyCommand command(long actor, String operation, IdempotencyKey key, String fingerprint) {
        return new IdempotencyCommand(PRINCIPAL, actor, operation, key.value(), fingerprint);
    }

    private BusinessResult<RegularClosureResponse> success(RegularClosureVersion target) {
        return new BusinessResult<>(HttpStatus.OK.value(), "SUCCESS", RESOURCE,
                target.getStoreId() + ":REGULAR:" + target.getVersionNumber(), RegularClosureResponse.from(target));
    }

    private <T> ScheduleCommandResult<T> result(IdempotentOutcome outcome, Class<T> type) {
        return new ScheduleCommandResult<>(outcome.httpStatus(), objectMapper.treeToValue(outcome.data(), type));
    }

    private void audit(RegularClosureVersion target, Long actor,
                       Long previousActiveVersion, Long newActiveVersion,
                       String action, String previous, String next, String zone,
                       Instant requestedAt, Instant effectiveAt, String reason,
                       String requestId, ScheduleAuditOutcome outcome) {
        StoreClosureActorType actorType = actor == null
                ? StoreClosureActorType.SYSTEM
                : StoreClosureActorType.STORE_OPERATOR;
        auditRepository.save(StoreClosureAuditEvent.record(target.getStoreId(), actorType, actor, "REGULAR",
                Long.toString(target.getId()), previousActiveVersion, newActiveVersion,
                action, previous, next, zone, requestedAt, effectiveAt,
                clock.instant(), reason, requestId, outcome,
                null, null, null, null));
    }

    private ServiceException conflict() { return new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT); }
}
