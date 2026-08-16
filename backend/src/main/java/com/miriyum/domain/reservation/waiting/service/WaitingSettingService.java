package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.*;
import com.miriyum.domain.reservation.waiting.entity.*;
import com.miriyum.domain.reservation.waiting.repository.*;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class WaitingSettingService {
    private final WaitingStoreAuthorityPort authorityPort;
    private final WaitingSettingRepository settingRepository;
    private final WaitingSettingAuditRepository auditRepository;
    private final WaitingClosureService closureService;
    private final IdempotencyExecutor idempotencyExecutor;
    private final WaitingSettingTransactionExecutor transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WaitingSettingService(WaitingStoreAuthorityPort authorityPort,
            WaitingSettingRepository settingRepository,
            WaitingSettingAuditRepository auditRepository,
            WaitingClosureService closureService,
            IdempotencyExecutor idempotencyExecutor,
            WaitingSettingTransactionExecutor transactions,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorityPort = Objects.requireNonNull(authorityPort);
        this.settingRepository = Objects.requireNonNull(settingRepository);
        this.auditRepository = Objects.requireNonNull(auditRepository);
        this.closureService = Objects.requireNonNull(closureService);
        this.idempotencyExecutor = Objects.requireNonNull(idempotencyExecutor);
        this.transactions = Objects.requireNonNull(transactions);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public WaitingSettingSnapshot get(long operatorId, long storeId) {
        authorityPort.requireRead(operatorId, storeId);
        return settingRepository.findByStoreId(storeId)
                .map(WaitingSettingSnapshot::from)
                .orElseGet(() -> WaitingSettingSnapshot.defaults(storeId));
    }

    @Transactional(readOnly = true)
    public WaitingSettingDeactivationImpact inspectDeactivation(long operatorId, long storeId) {
        authorityPort.requireRead(operatorId, storeId);
        long version = settingRepository.findByStoreId(storeId)
                .map(WaitingSetting::getVersion).orElse(0L);
        WaitingActiveTeamImpact impact = closureService.inspectActiveTeams(operatorId, storeId);
        return new WaitingSettingDeactivationImpact(storeId, version, impact.activeTeamCount());
    }

    public WaitingSettingCommandResult replace(long operatorId, long storeId,
            IdempotencyKey key, WaitingSettingUpdateRequest request) {
        authorityPort.requireMutation(operatorId, storeId);
        validate(request);
        try {
            return transactions.execute(() -> executeIdempotent(operatorId, storeId, key, request));
        } catch (RuntimeException failure) {
            if (WaitingSettingFailureClassifier.isConcurrentModification(failure)) {
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public boolean openAutomatically(long storeId, long expectedVersion) {
        return settingRepository.findByStoreId(storeId)
                .map(setting -> setting.canOpenAutomatically(expectedVersion))
                .orElse(false);
    }

    private WaitingSettingCommandResult executeIdempotent(long operatorId, long storeId,
            IdempotencyKey key, WaitingSettingUpdateRequest request) {
        IdempotencyCommand command = new IdempotencyCommand(
                "store-operator", operatorId, "WAITING_SETTING_REPLACE", key.value(),
                RequestFingerprint.of(fingerprint(storeId, request)));
        IdempotentOutcome outcome = idempotencyExecutor.execute(command,
                () -> apply(operatorId, storeId, key, request));
        Object data = outcome.httpStatus() == HttpStatus.ACCEPTED.value()
                ? objectMapper.treeToValue(outcome.data(), WaitingClosureJobSnapshot.class)
                : objectMapper.treeToValue(outcome.data(), WaitingSettingSnapshot.class);
        return new WaitingSettingCommandResult(outcome.httpStatus(), data);
    }

    private BusinessResult<?> apply(long operatorId, long storeId,
            IdempotencyKey key, WaitingSettingUpdateRequest request) {
        Instant now = clock.instant();
        WaitingSetting setting = settingRepository.findByStoreId(storeId).orElse(null);
        long currentVersion = setting == null ? 0L : setting.getVersion();
        if (currentVersion != request.expectedVersion()) {
            throw new ServiceException(ReservationErrorCode.WAITING_SETTING_VERSION_CONFLICT);
        }

        WaitingActiveTeamImpact impact = null;
        if (!request.enabled()) {
            impact = closureService.inspectActiveTeams(operatorId, storeId);
            if (impact.activeTeamCount() > 0 && request.disableAction() == null) {
                throw new ServiceException(ReservationErrorCode.WAITING_DISABLE_ACTION_REQUIRED);
            }
        }

        if (setting == null) {
            setting = WaitingSetting.create(storeId, request.enabled(), request.receptionMode(),
                    request.advanceOpenMinutes(), now);
        } else {
            setting.replace(request.expectedVersion(), request.enabled(), request.receptionMode(),
                    request.advanceOpenMinutes(), now);
        }
        setting = settingRepository.saveAndFlush(setting);
        auditRepository.saveAndFlush(WaitingSettingAudit.record(setting, operatorId, now));

        if (!request.enabled() && request.disableAction() == WaitingDisableAction.CLOSE_ACTIVE_TEAMS) {
            WaitingClosureCommandResult closure = closureService.startClosure(
                    operatorId, storeId, key, setting.getVersion());
            return new BusinessResult<>(HttpStatus.ACCEPTED.value(), "SUCCESS",
                    "WAITING_CLOSURE_JOB", closure.data().jobId(), closure.data());
        }
        WaitingSettingSnapshot snapshot = WaitingSettingSnapshot.from(setting);
        return new BusinessResult<>(HttpStatus.OK.value(), "SUCCESS",
                "WAITING_SETTING", Long.toString(storeId), snapshot);
    }

    private static void validate(WaitingSettingUpdateRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || request.enabled() == null || request.receptionMode() == null
                || request.advanceOpenMinutes() == null || request.advanceOpenMinutes() < 0
                || request.advanceOpenMinutes() > 180
                || (!request.enabled() && request.receptionMode() != WaitingReceptionMode.PAUSED)
                || (request.enabled() && request.disableAction() != null)) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private static String fingerprint(long storeId, WaitingSettingUpdateRequest request) {
        return "PUT|/api/v1/store-operators/stores/{storeId}/waiting-settings|storeId=" + storeId
                + "|expectedVersion=" + request.expectedVersion()
                + "|enabled=" + request.enabled()
                + "|receptionMode=" + request.receptionMode()
                + "|advanceOpenMinutes=" + request.advanceOpenMinutes()
                + "|disableAction=" + request.disableAction();
    }
}
