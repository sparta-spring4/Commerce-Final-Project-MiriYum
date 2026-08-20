package com.miriyum.domain.store.service;

import com.miriyum.domain.store.dto.contract.StoreDashboardAuthority;
import com.miriyum.domain.store.dto.contract.StoreServiceProfile;
import com.miriyum.domain.store.dto.contract.StoreWaitingReceptionProfile;
import com.miriyum.domain.store.dto.contract.StoreWaitingLocationProfile;
import com.miriyum.domain.store.dto.storeoperator.ManagedStoreResponse;
import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreModesRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreUpdateRequest;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.StoreBaseSettings;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.storeoperator.service.StoreOperatorAccountService;
import com.miriyum.global.exception.CommonErrorCode;
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
import java.util.Objects;
import java.util.Optional;
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
    private final StoreGeocodingPort geocodingPort;
    private final StoreGeocodingValidator geocodingValidator;
    private final StoreCommandTransactionExecutor transactionExecutor;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final StoreAdministrationService storeAdministrationService;

    public StoreCommandResult create(
            long operatorAccountId,
            IdempotencyKey idempotencyKey,
            StoreCreateRequest request
    ) {
        operatorAccountService.getMe(operatorAccountId);
        GeocodingPreflight geocoding = geocode(request.region(), request.address());

        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorAccountId,
                "STORE_REGISTER",
                idempotencyKey.value(),
                StoreCommandFingerprint.forCreate(request));

        IdempotentOutcome outcome = transactionExecutor.execute(() ->
                idempotencyExecutor.execute(command, () -> {
                    VerifiedStoreGeocoding verified = geocoding.requireVerified();
                    catalogPolicy.validate(request.storeCategoryCode(), request.tagCodes());
                    StoreModesRequest modes = request.modes();
                    LocalDateTime onboardingAcceptedAt =
                            LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE);
                    Store store = Store.createVerified(
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
                            REQUIRED_TERMS_VERSION,
                            verified);
                    Store saved = saveStore(store);
                    return success(HttpStatus.CREATED, saved);
                }));
        return commandResult(outcome);
    }

    @Transactional(readOnly = true)
    public ManagedStoreResponse getManagedStore(long operatorAccountId, long storeId) {
        operatorAccountService.getMe(operatorAccountId);
        return ManagedStoreResponse.from(loadManagedStore(operatorAccountId, storeId));
    }

    @Transactional(readOnly = true)
    public List<ManagedStoreResponse> getManagedStores(long operatorAccountId) {
        operatorAccountService.getMe(operatorAccountId);
        return storeRepository
                .findAllByStoreOperatorAccountIdOrderByIdAsc(operatorAccountId)
                .stream()
                .map(ManagedStoreResponse::from)
                .toList();
    }

    public StoreCommandResult update(
            long operatorAccountId,
            long storeId,
            IdempotencyKey idempotencyKey,
            StoreUpdateRequest request
    ) {
        operatorAccountService.getMe(operatorAccountId);
        requireStoreOwnership(operatorAccountId, storeId);
        GeocodingPreflight geocoding = updateGeocodingPreflight(
                operatorAccountId,
                storeId,
                request);

        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorAccountId,
                "STORE_UPDATE",
                idempotencyKey.value(),
                StoreCommandFingerprint.forUpdate(storeId, request));

        IdempotentOutcome outcome = transactionExecutor.execute(() ->
                idempotencyExecutor.execute(command, () -> {
                    Store store = loadManagedStoreForUpdate(operatorAccountId, storeId);
                    VerifiedStoreGeocoding verified = verifiedForUpdate(
                            store,
                            request,
                            geocoding);
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
                            request.tagCodes() == null
                                    ? null
                                    : Set.copyOf(request.tagCodes()),
                            modes == null ? null : modes.reservationEnabled(),
                            modes == null ? null : modes.menuHoldEnabled(),
                            modes == null ? null : modes.pickupEnabled(),
                            request.operationStatus(),
                            verified);
                    storeAdministrationService.recomposeAfterOperatorUpdate(
                            storeId,
                            new StoreBaseSettings(
                                    request.operationStatus(),
                                    modes == null ? null : modes.reservationEnabled(),
                                    modes == null ? null : modes.menuHoldEnabled(),
                                    modes == null ? null : modes.pickupEnabled()));
                    Store saved = saveStore(store);
                    return success(HttpStatus.OK, saved);
                }));
        return commandResult(outcome);
    }

    private GeocodingPreflight updateGeocodingPreflight(
            long operatorAccountId,
            long storeId,
            StoreUpdateRequest request
    ) {
        if (request.region() == null && request.address() == null) {
            return null;
        }
        Store snapshot = loadManagedStore(operatorAccountId, storeId);
        Region region = request.region() == null
                ? snapshot.getRegion()
                : request.region();
        String address = request.address() == null
                ? snapshot.getAddress()
                : request.address();
        return geocode(region, address, snapshot.getAddressVersion());
    }

    private GeocodingPreflight geocode(Region region, String address) {
        return geocode(region, address, null);
    }

    private GeocodingPreflight geocode(
            Region region,
            String address,
            Long sourceAddressVersion
    ) {
        try {
            return GeocodingPreflight.succeeded(
                    region,
                    address,
                    sourceAddressVersion,
                    geocodingValidator.validate(
                            region,
                            address,
                            geocodingPort.geocode(address),
                            clock.instant()));
        } catch (ServiceException failure) {
            return GeocodingPreflight.failed(
                    region,
                    address,
                    sourceAddressVersion,
                    failure);
        }
    }

    private VerifiedStoreGeocoding verifiedForUpdate(
            Store store,
            StoreUpdateRequest request,
            GeocodingPreflight geocoding
    ) {
        if (geocoding == null) {
            return null;
        }
        Region effectiveRegion = request.region() == null
                ? store.getRegion()
                : request.region();
        String effectiveAddress = request.address() == null
                ? store.getAddress()
                : request.address();
        geocoding.requireSource(
                effectiveRegion,
                effectiveAddress,
                store.getAddressVersion());
        return geocoding.requireVerified();
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
     * 조회 대상 매장의 부재와 비소유를 같은 not-found 결과로 숨긴다.
     */
    @Transactional(readOnly = true)
    public void requireConcealedReadOwnership(
            long operatorAccountId,
            long storeId
    ) {
        operatorAccountService.getMe(operatorAccountId);
        if (!storeRepository.existsByIdAndStoreOperatorAccountId(storeId, operatorAccountId)) {
            throw new ServiceException(StoreErrorCode.STORE_NOT_FOUND);
        }
    }

    /**
     * 대시보드 통계 조회에 필요한 소유권과 시간 경계를 공개 DTO로 반환한다.
     */
    @Transactional(readOnly = true)
    public StoreDashboardAuthority requireDashboardAuthority(
            long operatorAccountId,
            long storeId
    ) {
        operatorAccountService.getMe(operatorAccountId);
        Store store = loadManagedStore(operatorAccountId, storeId);
        return new StoreDashboardAuthority(
                store.getId(),
                store.getTimeZoneId(),
                store.getDashboardAuthorityVersion());
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

    /** 다른 도메인의 사용자 표시 문구에 필요한 공개 매장명만 조회한다. */
    @Transactional(readOnly = true)
    public Optional<String> findDisplayName(long storeId) {
        if (storeId <= 0) {
            return Optional.empty();
        }
        return storeRepository.findById(storeId).map(Store::getName);
    }

    /** 웨이팅 위치 판정에 필요한 승인 기준점만 공개 DTO로 반환한다. */
    @Transactional(readOnly = true)
    public StoreWaitingLocationProfile getWaitingLocationProfile(long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        boolean eligible = store.getVerificationStatus() == VerificationStatus.APPROVED
                && store.getOperationStatus() == OperationStatus.OPEN
                && store.getGeocodingStatus()
                == com.miriyum.domain.store.enums.GeocodingStatus.VERIFIED
                && store.getLatitude() != null
                && store.getLongitude() != null
                && store.getGeocodingAddressVersion() != null;
        return new StoreWaitingLocationProfile(
                store.getId(),
                eligible ? store.getLatitude() : null,
                eligible ? store.getLongitude() : null,
                eligible ? store.getGeocodingAddressVersion() : 0L,
                eligible);
    }

    /**
     * 웨이팅 일정 해석을 위해 매장 상태를 공개 계약으로 일괄 투영한다.
     */
    @Transactional(readOnly = true)
    public Map<Long, StoreWaitingReceptionProfile> getWaitingReceptionProfiles(
            Set<Long> storeIds
    ) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Map.of();
        }
        return storeRepository.findAllById(storeIds).stream()
                .map(StoreService::waitingReceptionProfile)
                .collect(Collectors.toUnmodifiableMap(
                        StoreWaitingReceptionProfile::storeId,
                        Function.identity()));
    }

    /**
     * 웨이팅 접수 명령을 위해 Store 행을 잠그고 현재 상태를 공개 DTO로 반환한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public StoreWaitingReceptionProfile inspectWaitingReceptionForUpdate(
            long storeId
    ) {
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        return waitingReceptionProfile(store);
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
        store.requirePlatformManagementAllowed();
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
        store.requirePlatformManagementAllowed();
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

    private static StoreWaitingReceptionProfile waitingReceptionProfile(Store store) {
        boolean waitingReceptionEligible =
                store.getVerificationStatus() == VerificationStatus.APPROVED
                && store.getOperationStatus() == OperationStatus.OPEN;
        return new StoreWaitingReceptionProfile(
                store.getId(),
                store.getTimeZoneId(),
                waitingReceptionEligible);
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

    private record GeocodingPreflight(
            Region region,
            String address,
            Long sourceAddressVersion,
            VerifiedStoreGeocoding verified,
            ServiceException failure
    ) {

        private static GeocodingPreflight succeeded(
                Region region,
                String address,
                Long sourceAddressVersion,
                VerifiedStoreGeocoding verified
        ) {
            return new GeocodingPreflight(
                    region,
                    address,
                    sourceAddressVersion,
                    verified,
                    null);
        }

        private static GeocodingPreflight failed(
                Region region,
                String address,
                Long sourceAddressVersion,
                ServiceException failure
        ) {
            return new GeocodingPreflight(
                    region,
                    address,
                    sourceAddressVersion,
                    null,
                    failure);
        }

        private void requireSource(
                Region effectiveRegion,
                String effectiveAddress,
                long effectiveAddressVersion
        ) {
            if (sourceAddressVersion == null
                    || sourceAddressVersion != effectiveAddressVersion
                    || region != effectiveRegion
                    || !Objects.equals(address, effectiveAddress)) {
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }
        }

        private VerifiedStoreGeocoding requireVerified() {
            if (failure != null) {
                throw failure;
            }
            return verified;
        }
    }
}
