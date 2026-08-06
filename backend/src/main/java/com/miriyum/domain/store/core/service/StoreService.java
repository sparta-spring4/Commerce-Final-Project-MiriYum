package com.miriyum.domain.store.core.service;

import com.miriyum.domain.store.core.dto.ManagedStoreResponse;
import com.miriyum.domain.store.core.dto.StoreCreateRequest;
import com.miriyum.domain.store.core.dto.StoreModesRequest;
import com.miriyum.domain.store.core.dto.StoreUpdateRequest;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.enums.VerificationStatus;
import com.miriyum.domain.store.core.model.VerifiedStoreGeocoding;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.dto.MenuTransactionEligibility;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.entity.MenuVersion;
import com.miriyum.domain.store.menu.repository.MenuRepository;
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
import java.util.Objects;
import java.util.Set;
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
    private final MenuRepository menuRepository;
    private final StoreCatalogPolicy catalogPolicy;
    private final StoreGeocodingPort geocodingPort;
    private final StoreGeocodingValidator geocodingValidator;
    private final StoreCommandTransactionExecutor transactionExecutor;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

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

    /**
     * 신규 메뉴 홀드·픽업 거래를 위해 Store와 Menu를 잠금 순서대로 검증한다.
     *
     * @param storeId 대상 매장 식별자
     * @param menuId 대상 메뉴 식별자
     * @return 현재 게시 버전과 최종 거래 기능 판정
     * @throws ServiceException 매장·메뉴가 없거나 신규 거래를 받을 수 없는 경우
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuTransactionEligibility requireMenuTransactionEligibility(
            long storeId,
            long menuId
    ) {
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        requireTransactionState(store);

        Menu menu = menuRepository.findByIdForUpdate(menuId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.MENU_NOT_FOUND));
        if (menu.getStoreId() != storeId) {
            throw new ServiceException(StoreErrorCode.MENU_NOT_FOUND);
        }

        MenuVersion published = menu.requireTransactionVersion();
        boolean menuHoldEligible = store.isReservationEnabled()
                && store.isMenuHoldEnabled()
                && published.isHoldSelectionAllowed();
        boolean pickupEligible = store.isPickupEnabled()
                && published.isPickupSelectionAllowed();
        return new MenuTransactionEligibility(
                storeId,
                menuId,
                published.getVersionNumber(),
                published.getName(),
                published.getPrice(),
                menuHoldEligible,
                pickupEligible);
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

    private void requireTransactionState(Store store) {
        if (store.getVerificationStatus() != VerificationStatus.APPROVED) {
            throw new ServiceException(
                    StoreErrorCode.VERIFICATION_STATE_CONFLICT);
        }
        if (store.getOperationStatus() != OperationStatus.OPEN) {
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
