package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketCreateCommand;
import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketView;
import com.miriyum.domain.menuhold.inventory.dto.InventoryPolicyChange;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryPolicyAudit;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryPolicyAuditRepository;
import com.miriyum.domain.store.core.service.StoreScheduleAuthority;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.menu.dto.MenuTransactionEligibility;
import com.miriyum.domain.store.menu.service.MenuQueryService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class MenuInventoryAdminCommandService {

    private static final String NAMESPACE = "store-operator";
    private static final String CREATE_COMMAND = "MENU_INVENTORY_CREATE";
    private static final String UPDATE_COMMAND = "MENU_INVENTORY_UPDATE";

    private final StoreService storeService;
    private final MenuQueryService menuQueryService;
    private final MenuInventoryBucketRepository bucketRepository;
    private final MenuInventoryPolicyAuditRepository auditRepository;
    private final MenuInventoryPolicyService policyService;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuInventoryCommandResult create(
            long operatorId,
            long storeId,
            IdempotencyKey key,
            InventoryBucketCreateCommand request
    ) {
        menuQueryService.get(operatorId, storeId, request.menuId());
        IdempotencyCommand command = new IdempotencyCommand(
                NAMESPACE, operatorId, CREATE_COMMAND, key.value(),
                fingerprint(storeId, request));
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            StoreScheduleAuthority store =
                    storeService.requireSchedulePublicationAuthority(operatorId, storeId);
            requireAvailableEligibility(storeId, request.menuId(),
                    request.onlineHoldCapacity(), request.sharedCapacity(),
                    request.sharedOnlineAllowed(), request.availabilityStatus());
            MenuInventoryBucket bucket = MenuInventoryBucket.create(
                    request.menuId(), request.serviceDate(), request.startTime(),
                    request.endDate(), request.endTime(), store.timeZoneId(), 1L,
                    request.totalSupply(), request.onlineHoldCapacity(),
                    request.onsiteCapacity(), request.sharedCapacity(),
                    request.sharedOnlineAllowed(), request.availabilityStatus());
            MenuInventoryBucket saved = saveInitial(bucket);
            auditRepository.saveAndFlush(MenuInventoryPolicyAudit.created(
                    operatorId, CREATE_COMMAND, key.value(), saved));
            InventoryBucketView view = InventoryBucketView.from(saved);
            return new BusinessResult<>(HttpStatus.CREATED.value(), "SUCCESS",
                    "MENU_INVENTORY_BUCKET", String.valueOf(saved.getId()), view);
        });
        return new MenuInventoryCommandResult(outcome.httpStatus(),
                objectMapper.treeToValue(outcome.data(), InventoryBucketView.class));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuInventoryCommandResult update(
            long operatorId,
            long storeId,
            long bucketId,
            IdempotencyKey key,
            InventoryPolicyChange request
    ) {
        MenuInventoryBucket selected = bucketRepository.findById(bucketId)
                .orElseThrow(() -> new ServiceException(
                        MenuHoldErrorCode.BUCKET_NOT_FOUND));
        menuQueryService.get(operatorId, storeId, selected.getMenuId());
        IdempotentOutcome outcome = idempotencyExecutor.execute(
                new IdempotencyCommand(
                        NAMESPACE, operatorId, UPDATE_COMMAND, key.value(),
                        updateFingerprint(storeId, bucketId, request)),
                () -> {
                    requireAvailableEligibility(storeId, selected.getMenuId(),
                            request.onlineHoldCapacity(), request.sharedCapacity(),
                            request.sharedOnlineAllowed(), request.availabilityStatus());
                    MenuInventoryBucket saved = policyService.publishNextPolicy(
                            operatorId, UPDATE_COMMAND, key.value(), bucketId, request);
                    InventoryBucketView view = InventoryBucketView.from(saved);
                    return new BusinessResult<>(HttpStatus.OK.value(), "SUCCESS",
                            "MENU_INVENTORY_BUCKET", String.valueOf(saved.getId()), view);
                });
        return new MenuInventoryCommandResult(outcome.httpStatus(),
                objectMapper.treeToValue(outcome.data(), InventoryBucketView.class));
    }

    private MenuInventoryBucket saveInitial(MenuInventoryBucket bucket) {
        try {
            return bucketRepository.saveAndFlush(bucket);
        } catch (DataIntegrityViolationException exception) {
            if (containsConstraint(exception, "uk_menu_inventory_bucket_key")) {
                ServiceException conflict = new ServiceException(
                        MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
                conflict.initCause(exception);
                throw conflict;
            }
            throw exception;
        }
    }

    private void requireAvailableEligibility(
            long storeId,
            long menuId,
            int onlineCapacity,
            int sharedCapacity,
            boolean sharedOnlineAllowed,
            InventoryAvailabilityStatus status
    ) {
        if (status != InventoryAvailabilityStatus.AVAILABLE) {
            return;
        }
        MenuTransactionEligibility eligibility =
                storeService.requireMenuTransactionEligibility(storeId, menuId);
        if (onlineCapacity + (sharedOnlineAllowed ? sharedCapacity : 0) <= 0) {
            throw new ServiceException(
                    MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }
        if (!eligibility.menuHoldEligible()) {
            throw new ServiceException(MenuHoldErrorCode.INELIGIBLE_MENU);
        }
    }

    private static boolean containsConstraint(Throwable failure, String marker) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains(marker)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String fingerprint(
            long storeId,
            InventoryBucketCreateCommand request
    ) {
        return RequestFingerprint.of(String.join("|",
                "CREATE", String.valueOf(storeId), String.valueOf(request.menuId()),
                request.serviceDate().toString(), request.startTime().toString(),
                request.endDate().toString(), request.endTime().toString(),
                String.valueOf(request.totalSupply()),
                String.valueOf(request.onlineHoldCapacity()),
                String.valueOf(request.onsiteCapacity()),
                String.valueOf(request.sharedCapacity()),
                String.valueOf(request.sharedOnlineAllowed()),
                request.availabilityStatus().name()));
    }

    private static String updateFingerprint(
            long storeId,
            long bucketId,
            InventoryPolicyChange request
    ) {
        return RequestFingerprint.of(String.join("|",
                "UPDATE", String.valueOf(storeId), String.valueOf(bucketId),
                String.valueOf(request.totalSupply()),
                String.valueOf(request.onlineHoldCapacity()),
                String.valueOf(request.onsiteCapacity()),
                String.valueOf(request.sharedCapacity()),
                String.valueOf(request.sharedOnlineAllowed()),
                request.availabilityStatus().name()));
    }
}
