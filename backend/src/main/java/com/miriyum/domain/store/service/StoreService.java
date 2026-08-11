package com.miriyum.domain.store.service;

import com.miriyum.domain.store.dto.storeoperator.ManagedStoreResponse;
import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreModesRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreUpdateRequest;
import com.miriyum.domain.store.dto.contract.StoreServiceProfile;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.storeoperator.service.StoreOperatorAccountService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class StoreService {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String RESOURCE_TYPE = "STORE";
    private static final String SUCCESS_RESPONSE_CODE = "SUCCESS";
    private static final String ACTIVE_BUSINESS_NUMBER_CONSTRAINT =
            "uk_stores_active_business_number";
    private static final String REQUIRED_TERMS_VERSION =
            "STORE_ONBOARDING_REQUIRED_TERMS_V1";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final StoreOperatorAccountService operatorAccountService;
    private final StoreRepository storeRepository;
    private final StoreCatalogPolicy catalogPolicy;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public StoreCommandResult create(
            long operatorAccountId,
            IdempotencyKey idempotencyKey,
            StoreCreateRequest request
    ) {
        operatorAccountService.getMe(operatorAccountId);

        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorAccountId,
                "STORE_REGISTER",
                idempotencyKey.value(),
                StoreCommandFingerprint.forCreate(request));

        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            catalogPolicy.validate(request.storeCategoryCode(), request.tagCodes());
            StoreModesRequest modes = request.modes();
            LocalDateTime onboardingAcceptedAt =
                    LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE);
            Store store = Store.create(
                    operatorAccountId,
                    request.businessRegistrationNumber(),
                    request.businessType(),
                    request.name(),
                    request.description(),
                    request.region(),
                    request.address(),
                    request.storeCategoryCode(),
                    Set.copyOf(request.tagCodes()),
                    modes.reservationEnabled(),
                    modes.menuHoldEnabled(),
                    modes.pickupEnabled(),
                    request.timeZoneId(),
                    onboardingAcceptedAt,
                    REQUIRED_TERMS_VERSION);
            Store saved = saveStore(store);
            return success(HttpStatus.CREATED, saved);
        });
        return commandResult(outcome);
    }

    @Transactional(readOnly = true)
    public ManagedStoreResponse getManagedStore(long operatorAccountId, long storeId) {
        operatorAccountService.getMe(operatorAccountId);
        return ManagedStoreResponse.from(loadManagedStore(operatorAccountId, storeId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public StoreCommandResult update(
            long operatorAccountId,
            long storeId,
            IdempotencyKey idempotencyKey,
            StoreUpdateRequest request
    ) {
        operatorAccountService.getMe(operatorAccountId);
        requireStoreOwnership(operatorAccountId, storeId);

        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorAccountId,
                "STORE_UPDATE",
                idempotencyKey.value(),
                StoreCommandFingerprint.forUpdate(storeId, request));

        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            Store store = loadManagedStoreForUpdate(operatorAccountId, storeId);
            String categoryCode = request.storeCategoryCode() == null
                    ? store.getStoreCategoryCode()
                    : request.storeCategoryCode();
            List<String> tagCodes = request.tagCodes() == null
                    ? List.copyOf(store.getTagCodes())
                    : request.tagCodes();
            catalogPolicy.validate(categoryCode, tagCodes);
            StoreModesRequest modes = request.modes();
            store.update(
                    request.name(),
                    request.description(),
                    request.region(),
                    request.address(),
                    request.storeCategoryCode(),
                    request.tagCodes() == null ? null : Set.copyOf(request.tagCodes()),
                    modes == null ? null : modes.reservationEnabled(),
                    modes == null ? null : modes.menuHoldEnabled(),
                    modes == null ? null : modes.pickupEnabled(),
                    request.operationStatus());
            Store saved = saveStore(store);
            return success(HttpStatus.OK, saved);
        });
        return commandResult(outcome);
    }

    @Transactional(readOnly = true)
    public void requireManagementOwnership(
            long operatorAccountId,
            long storeId
    ) {
        operatorAccountService.getMe(operatorAccountId);
        requireStoreOwnership(operatorAccountId, storeId);
    }

    /**
     * 일정 도메인의 일괄 판정을 위해 매장 상태를 공개 계약으로 투영한다.
     *
     * @param storeIds 조회할 매장 식별자 집합
     * @return 존재하는 매장만 포함한 식별자별 서비스 프로필
     */
    @Transactional(readOnly = true)
    public Map<Long, StoreServiceProfile> getServiceProfiles(Set<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Map.of();
        }
        return storeRepository.findAllById(storeIds).stream()
                .map(StoreService::serviceProfile)
                .collect(Collectors.toUnmodifiableMap(
                        StoreServiceProfile::storeId,
                        Function.identity()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public StoreScheduleAuthority requireSchedulePublicationAuthority(
            long operatorAccountId,
            long storeId
    ) {
        operatorAccountService.getMe(operatorAccountId);
        Store store = loadManagedStoreForUpdate(operatorAccountId, storeId);
        requireScheduleState(store);
        return scheduleAuthority(store);
    }

    /**
     * 메뉴 신규 명령을 위해 Store 행을 잠그고 현재 운영 가능 상태를 검증한다.
     *
     * @param operatorAccountId 인증된 매장 운영자 계정 식별자
     * @param storeId 대상 매장 식별자
     * @return 메뉴 콘텐츠 검증에 필요한 중앙 매장 판정
     * @throws ServiceException 소유권이 없거나 현재 매장 상태에서 메뉴를 변경할 수 없는 경우
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public StoreMenuAuthority requireMenuMutationAuthority(
            long operatorAccountId,
            long storeId
    ) {
        operatorAccountService.getMe(operatorAccountId);
        Store store = loadManagedStoreForUpdate(operatorAccountId, storeId);
        requireScheduleState(store);
        return new StoreMenuAuthority(store.getId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public StoreScheduledActivationDecision inspectScheduledActivation(
            long storeId
    ) {
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        boolean activationAllowed =
                store.getVerificationStatus() == VerificationStatus.APPROVED
                && store.getOperationStatus() != OperationStatus.CLOSED;
        return new StoreScheduledActivationDecision(
                store.getId(),
                store.getTimeZoneId(),
                activationAllowed);
    }

    private void requireScheduleState(Store store) {
        if (store.getVerificationStatus() != VerificationStatus.APPROVED) {
            throw new ServiceException(
                    StoreErrorCode.VERIFICATION_STATE_CONFLICT);
        }
        if (store.getOperationStatus() == OperationStatus.CLOSED) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
    }

    private StoreScheduleAuthority scheduleAuthority(Store store) {
        return new StoreScheduleAuthority(store.getId(), store.getTimeZoneId());
    }

    private Store loadManagedStore(long operatorAccountId, long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        store.requireManagedBy(operatorAccountId);
        return store;
    }

    private void requireStoreOwnership(long operatorAccountId, long storeId) {
        long ownerAccountId = storeRepository.findOperatorAccountIdById(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        if (ownerAccountId != operatorAccountId) {
            throw new ServiceException(StoreErrorCode.ACCESS_DENIED);
        }
    }

    private Store loadManagedStoreForUpdate(long operatorAccountId, long storeId) {
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        store.requireManagedBy(operatorAccountId);
        return store;
    }

    private Store saveStore(Store store) {
        try {
            return storeRepository.saveAndFlush(store);
        } catch (DataIntegrityViolationException exception) {
            if (containsConstraint(exception, ACTIVE_BUSINESS_NUMBER_CONSTRAINT)) {
                throw new ServiceException(StoreErrorCode.BUSINESS_NUMBER_CONFLICT);
            }
            throw exception;
        }
    }

    private static boolean containsConstraint(Throwable failure, String constraint) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(constraint)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static StoreServiceProfile serviceProfile(Store store) {
        boolean reservationAccepting =
                store.getVerificationStatus() == VerificationStatus.APPROVED
                && store.getOperationStatus() == OperationStatus.OPEN
                && store.isReservationEnabled();
        return new StoreServiceProfile(
                store.getId(),
                store.getTimeZoneId(),
                reservationAccepting);
    }

    private static BusinessResult<ManagedStoreResponse> success(
            HttpStatus status,
            Store store
    ) {
        return new BusinessResult<>(
                status.value(),
                SUCCESS_RESPONSE_CODE,
                RESOURCE_TYPE,
                String.valueOf(store.getId()),
                ManagedStoreResponse.from(store));
    }

    private StoreCommandResult commandResult(IdempotentOutcome outcome) {
        ObjectNode replayPayload = (ObjectNode) outcome.data().deepCopy();
        replayPayload.remove("pickupEligibility");
        ManagedStoreResponse response =
                objectMapper.treeToValue(replayPayload, ManagedStoreResponse.class);
        return new StoreCommandResult(outcome.httpStatus(), response);
    }
}
