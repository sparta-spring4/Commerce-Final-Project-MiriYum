package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingActiveTeamImpact;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureJobSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingSettingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingSettingDeactivationImpact;
import com.miriyum.domain.reservation.waiting.dto.WaitingSettingSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingSettingUpdateRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingDisableAction;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import com.miriyum.domain.reservation.waiting.entity.WaitingSettingAudit;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingAuditRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 매장별 웨이팅 설정 조회와 version 조건부 전체 교체를 조정한다.
 *
 * <p>매장 권한, 멱등성, 설정 감사와 선택적인 활성 팀 종결 작업을 하나의 명령 경계에서
 * 처리한다. 자동 오픈 작업 실행은 이 서비스의 책임이 아니며 별도 worker가 실제 변경과
 * 설정 version CAS를 원자적으로 수행해야 한다.
 */
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

    /**
     * 현재 매장 설정을 조회하며 저장된 설정이 없으면 실패 폐쇄 기본값을 반환한다.
     *
     * @param operatorId 인증된 매장 운영자 계정 ID
     * @param storeId 조회할 매장 ID
     * @return 현재 설정 또는 {@code false / PAUSED / 60 / version 0} 기본값
     */
    @Transactional(readOnly = true)
    public WaitingSettingSnapshot get(long operatorId, long storeId) {
        authorityPort.requireRead(operatorId, storeId);
        return settingRepository.findByStoreId(storeId)
                .map(WaitingSettingSnapshot::from)
                .orElseGet(() -> WaitingSettingSnapshot.defaults(storeId));
    }

    /**
     * 설정 비활성화 전에 #272 공개 계약으로 활성 팀 영향을 조회한다.
     *
     * @param operatorId 인증된 매장 운영자 계정 ID
     * @param storeId 조회할 매장 ID
     * @return 현재 설정 version과 활성 팀 수
     */
    @Transactional(readOnly = true)
    public WaitingSettingDeactivationImpact inspectDeactivation(long operatorId, long storeId) {
        authorityPort.requireRead(operatorId, storeId);
        long version = settingRepository.findByStoreId(storeId)
                .map(WaitingSetting::getVersion).orElse(0L);
        WaitingActiveTeamImpact impact = closureService.inspectActiveTeams(operatorId, storeId);
        return new WaitingSettingDeactivationImpact(
                Long.toString(storeId), version, impact.activeTeamCount());
    }

    /**
     * 요청의 {@code expectedVersion}을 기준으로 설정 전체를 멱등 교체한다.
     *
     * <p>비활성화 시 활성 팀 처리 방식이 필요하며, {@code CLOSE_ACTIVE_TEAMS}이면 설정 저장과
     * #272 closure job 생성을 같은 트랜잭션에 결박해 202 결과를 반환한다.
     *
     * @param operatorId 인증된 매장 운영자 계정 ID
     * @param storeId 변경할 매장 ID
     * @param key 요청 재시도에 사용할 멱등 키
     * @param request 기대 version을 포함한 전체 설정
     * @return 200 설정 snapshot 또는 202 closure job snapshot
     */
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
        WaitingSetting setting = settingRepository.findByStoreIdForUpdate(storeId).orElse(null);
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
