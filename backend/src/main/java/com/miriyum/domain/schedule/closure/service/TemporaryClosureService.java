package com.miriyum.domain.schedule.closure.service;

import com.miriyum.domain.schedule.closure.dto.storeoperator.*;
import com.miriyum.domain.schedule.closure.entity.RegularClosureVersion;
import com.miriyum.domain.schedule.closure.entity.StoreClosureAuditEvent;
import com.miriyum.domain.schedule.closure.entity.TemporaryClosure;
import com.miriyum.domain.schedule.closure.model.StoreClosureActorType;
import com.miriyum.domain.schedule.closure.repository.*;
import com.miriyum.domain.store.service.StoreScheduleAuthority;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.schedule.entity.StoreScheduleState;
import com.miriyum.domain.schedule.model.ScheduleAuditOutcome;
import com.miriyum.domain.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.schedule.service.ScheduleCommandResult;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.*;
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
public class TemporaryClosureService {
    private final StoreService storeService;
    private final StoreScheduleStateRepository stateRepository;
    private final RegularClosureVersionRepository regularRepository;
    private final TemporaryClosureRepository temporaryRepository;
    private final StoreClosureAuditEventRepository auditRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<TemporaryClosureResponse> create(long actor, long storeId,
            IdempotencyKey key, TemporaryClosureCreateRequest request) {
        storeService.requireManagementOwnership(actor, storeId);
        return execute(actor, "STORE_TEMPORARY_CLOSURE_CREATE", key,
                StoreClosureFingerprint.temporaryCreate(storeId, request), () -> {
                    StoreScheduleAuthority authority = storeService.requireSchedulePublicationAuthority(actor, storeId);
                    stateRepository.initialize(storeId);
                    StoreScheduleState state = stateRepository.findForUpdateByStoreId(storeId).orElseThrow(this::conflict);
                    Instant start = request.startAt().toInstant();
                    Instant end = request.endAt().toInstant();
                    Instant requestedAt = clock.instant();
                    if (start.isBefore(requestedAt) || !end.isAfter(requestedAt)
                            || overlapsRegular(state, start, end, authority.timeZoneId())) throw conflict();
                    TemporaryClosure closure = temporaryRepository.saveAndFlush(TemporaryClosure.create(
                            storeId, start, end, authority.timeZoneId(), request.reason(), request.publicMessage()));
                    audit(closure, actor, "CREATED", null,
                            closure.statusAt(requestedAt).name(), key.value(), null,
                            requestedAt, null, closure.getEndAt());
                    return closure;
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<TemporaryClosureResponse> changeEndAt(long actor, long storeId, long closureId,
            IdempotencyKey key, TemporaryClosureEndAtRequest request) {
        storeService.requireManagementOwnership(actor, storeId);
        return execute(actor, "STORE_TEMPORARY_CLOSURE_END_CHANGE", key,
                StoreClosureFingerprint.temporaryEnd(storeId, closureId, request), () -> {
                    StoreScheduleAuthority authority = storeService.requireSchedulePublicationAuthority(actor, storeId);
                    stateRepository.initialize(storeId);
                    StoreScheduleState state = stateRepository.findForUpdateByStoreId(storeId).orElseThrow(this::conflict);
                    TemporaryClosure closure = temporaryRepository.findForUpdate(storeId, closureId).orElseThrow(this::conflict);
                    Instant requestedAt = clock.instant();
                    String before = closure.statusAt(requestedAt).name();
                    Instant previousEndAt = closure.getEndAt();
                    Instant changedEnd = request.endAt().toInstant();
                    if (overlapsRegular(state, closure.getStartAt(), changedEnd, authority.timeZoneId())) {
                        throw conflict();
                    }
                    closure.changeEndAt(changedEnd, requestedAt);
                    audit(closure, actor, "END_CHANGED", before,
                            closure.statusAt(requestedAt).name(), key.value(), request.changeReason(),
                            requestedAt, previousEndAt, closure.getEndAt());
                    return closure;
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<TemporaryClosureResponse> cancel(long actor, long storeId, long closureId,
            IdempotencyKey key, TemporaryClosureCancellationRequest request) {
        storeService.requireManagementOwnership(actor, storeId);
        return execute(actor, "STORE_TEMPORARY_CLOSURE_CANCEL", key,
                StoreClosureFingerprint.temporaryCancel(storeId, closureId, request), () -> {
                    StoreScheduleAuthority authority = storeService.requireSchedulePublicationAuthority(actor, storeId);
                    stateRepository.initialize(storeId);
                    stateRepository.findForUpdateByStoreId(storeId).orElseThrow(this::conflict);
                    TemporaryClosure closure = temporaryRepository.findForUpdate(storeId, closureId).orElseThrow(this::conflict);
                    Instant requestedAt = clock.instant();
                    String before = closure.statusAt(requestedAt).name();
                    closure.cancel(requestedAt);
                    audit(closure, actor, "CANCELLED", before, "CANCELLED", key.value(),
                            request.changeReason(), requestedAt, closure.getEndAt(), closure.getEndAt());
                    return closure;
                });
    }

    private boolean overlapsRegular(StoreScheduleState state, Instant start, Instant end, String zoneId) {
        if (state.getActiveRegularClosureVersionId() == null) return false;
        RegularClosureVersion regular = regularRepository.findById(state.getActiveRegularClosureVersionId())
                .orElseThrow(this::conflict);
        ZoneId zone = ZoneId.of(zoneId);
        LocalDate first = start.atZone(zone).toLocalDate();
        LocalDate last = end.minusNanos(1).atZone(zone).toLocalDate();
        return first.datesUntil(last.plusDays(1)).anyMatch(regular::isClosedOn);
    }

    private ScheduleCommandResult<TemporaryClosureResponse> execute(long actor, String operation, IdempotencyKey key,
            String fingerprint, java.util.function.Supplier<TemporaryClosure> work) {
        IdempotentOutcome outcome = idempotencyExecutor.execute(new IdempotencyCommand(
                "store-operator", actor, operation, key.value(), fingerprint), () -> {
            TemporaryClosure closure = work.get();
            return new BusinessResult<>(HttpStatus.OK.value(), "SUCCESS", "STORE_CLOSURE",
                    closure.getStoreId() + ":TEMPORARY:" + closure.getId(), TemporaryClosureResponse.from(closure, clock.instant()));
        });
        return new ScheduleCommandResult<>(outcome.httpStatus(),
                objectMapper.treeToValue(outcome.data(), TemporaryClosureResponse.class));
    }

    private void audit(TemporaryClosure closure, long actor, String action, String previous, String next,
            String requestId, String reason, Instant requestedAt,
            Instant previousEndAt, Instant newEndAt) {
        auditRepository.save(StoreClosureAuditEvent.record(closure.getStoreId(), StoreClosureActorType.STORE_OPERATOR,
                actor, "TEMPORARY",
                Long.toString(closure.getId()), null, null, action, previous, next,
                closure.getTimeZoneId(), requestedAt, closure.getStartAt(), clock.instant(), reason, requestId,
                ScheduleAuditOutcome.SUCCEEDED, closure.getStartAt(), previousEndAt, newEndAt,
                closure.getReason()));
    }

    private ServiceException conflict() { return new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT); }
}
