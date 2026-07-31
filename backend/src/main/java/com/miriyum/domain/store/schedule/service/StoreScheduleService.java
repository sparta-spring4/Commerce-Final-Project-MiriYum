package com.miriyum.domain.store.schedule.service;

import com.miriyum.domain.store.core.service.StoreScheduleAuthority;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.dto.OperatingHoursResponse;
import com.miriyum.domain.store.schedule.dto.ReservationTimeSlotsResponse;
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
