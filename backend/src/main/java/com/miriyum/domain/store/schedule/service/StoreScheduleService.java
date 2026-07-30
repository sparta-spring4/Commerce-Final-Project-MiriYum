package com.miriyum.domain.store.schedule.service;

import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.VerificationStatus;
import com.miriyum.domain.store.core.service.StoreManagementView;
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
import com.miriyum.domain.store.schedule.model.WeeklyInterval;
import com.miriyum.domain.store.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.ReservationScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
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
    private final WeeklySchedulePolicy schedulePolicy;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<OperatingHoursResponse> replaceOperatingHours(
            long operatorId,
            long storeId,
            IdempotencyKey key,
            WeeklyOperatingHoursRequest request
    ) {
        requirePublicationAuthority(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "STORE_OPERATING_HOURS_REPLACE",
                key.value(),
                StoreScheduleFingerprint.forOperating(storeId, request));

        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            StoreScheduleState state = initializeAndLock(storeId);
            List<WeeklyInterval> intervals =
                    schedulePolicy.validateOperating(request);
            OperatingScheduleVersion version = operatingRepository.saveAndFlush(
                    OperatingScheduleVersion.create(
                            storeId,
                            state.allocateOperatingVersion(),
                            intervals));
            state.activateOperating(version.getId());
            OperatingHoursResponse response = OperatingHoursResponse.from(version);
            return success(
                    resourceId(storeId, "OPERATING", response.version()),
                    response);
        });
        return commandResult(outcome, OperatingHoursResponse.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ScheduleCommandResult<ReservationTimeSlotsResponse>
            replaceReservationTimeSlots(
                    long operatorId,
                    long storeId,
                    IdempotencyKey key,
                    WeeklyReservationTimeSlotsRequest request
            ) {
        requirePublicationAuthority(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "STORE_RESERVATION_TIME_SLOTS_REPLACE",
                key.value(),
                StoreScheduleFingerprint.forReservation(storeId, request));

        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
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
                            ReservationScheduleVersion.create(
                                    storeId,
                                    state.allocateReservationVersion(),
                                    operatingVersionId,
                                    reservationIntervals));
            state.activateReservation(version.getId());
            ReservationTimeSlotsResponse response =
                    ReservationTimeSlotsResponse.from(version);
            return success(
                    resourceId(storeId, "RESERVATION", response.version()),
                    response);
        });
        return commandResult(outcome, ReservationTimeSlotsResponse.class);
    }

    private void requirePublicationAuthority(long operatorId, long storeId) {
        StoreManagementView authority =
                storeService.requireManagementAuthority(operatorId, storeId);
        if (authority.verificationStatus() != VerificationStatus.APPROVED) {
            throw new ServiceException(
                    StoreErrorCode.VERIFICATION_STATE_CONFLICT);
        }
        if (authority.operationStatus() == OperationStatus.CLOSED) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
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
