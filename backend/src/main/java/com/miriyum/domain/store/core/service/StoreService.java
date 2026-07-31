package com.miriyum.domain.store.core.service;

import com.miriyum.domain.store.core.dto.ManagedStoreResponse;
import com.miriyum.domain.store.core.dto.StoreCreateRequest;
import com.miriyum.domain.store.core.dto.StoreModesRequest;
import com.miriyum.domain.store.core.dto.StoreUpdateRequest;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.storeoperator.service.StoreOperatorAccountService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class StoreService {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String RESOURCE_TYPE = "STORE";
    private static final String SUCCESS_RESPONSE_CODE = "SUCCESS";
    private static final String ACTIVE_BUSINESS_NUMBER_CONSTRAINT =
            "uk_stores_active_business_number";

    private final StoreOperatorAccountService operatorAccountService;
    private final StoreRepository storeRepository;
    private final StoreCatalogPolicy catalogPolicy;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;

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
                    modes.pickupEnabled());
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
    public StoreManagementView requireManagementAuthority(
            long operatorAccountId,
            long storeId
    ) {
        operatorAccountService.getMe(operatorAccountId);
        Store store = loadManagedStore(operatorAccountId, storeId);
        return new StoreManagementView(
                store.getId(),
                store.getOperationStatus(),
                store.getVerificationStatus(),
                store.getPickupEligibility());
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
        ManagedStoreResponse response =
                objectMapper.treeToValue(outcome.data(), ManagedStoreResponse.class);
        return new StoreCommandResult(outcome.httpStatus(), response);
    }
}
