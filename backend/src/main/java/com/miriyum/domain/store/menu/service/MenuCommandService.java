package com.miriyum.domain.store.menu.service;

import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.VerificationStatus;
import com.miriyum.domain.store.core.service.StoreManagementView;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.dto.ManagedMenuResponse;
import com.miriyum.domain.store.menu.dto.MenuContentRequest;
import com.miriyum.domain.store.menu.dto.MenuPublicationRequest;
import com.miriyum.domain.store.menu.dto.MenuSellingStatusRequest;
import com.miriyum.domain.store.menu.dto.MenuVisibilityRequest;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.entity.MenuPublicationEvent;
import com.miriyum.domain.store.menu.entity.MenuVersion;
import com.miriyum.domain.store.menu.enums.MenuPublicationEventType;
import com.miriyum.domain.store.menu.enums.MenuPublicationMode;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.menu.repository.MenuPublicationEventRepository;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class MenuCommandService {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String RESOURCE_TYPE = "MENU";
    private static final String SUCCESS_RESPONSE_CODE = "SUCCESS";

    private final StoreService storeService;
    private final MenuRepository menuRepository;
    private final MenuPublicationEventRepository eventRepository;
    private final MenuContentPolicy contentPolicy;
    private final MenuDatabaseClock databaseClock;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuCommandResult create(
            long operatorId,
            long storeId,
            IdempotencyKey key,
            MenuContentRequest request
    ) {
        StoreManagementView store = requireMutableStore(operatorId, storeId);
        MenuContent content = contentPolicy.validateAndNormalize(request, store);
        return execute(operatorId, "MENU_CREATE", key,
                MenuCommandFingerprint.of("CREATE", storeId, 0, request), () -> {
                    Menu menu = menuRepository.saveAndFlush(
                            Menu.create(storeId, content, operatorId, databaseClock.now()));
                    return success(HttpStatus.CREATED, menu);
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuCommandResult update(
            long operatorId,
            long storeId,
            long menuId,
            IdempotencyKey key,
            MenuContentRequest request
    ) {
        StoreManagementView store = requireMutableStore(operatorId, storeId);
        MenuContent content = contentPolicy.validateAndNormalize(request, store);
        return execute(operatorId, "MENU_UPDATE", key,
                MenuCommandFingerprint.of("UPDATE", storeId, menuId, request), () -> {
                    Menu menu = lockedMenu(storeId, menuId);
                    menu.appendDraft(content, operatorId, databaseClock.now());
                    return success(HttpStatus.OK, menuRepository.saveAndFlush(menu));
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuCommandResult publish(
            long operatorId,
            long storeId,
            long menuId,
            IdempotencyKey key,
            MenuPublicationRequest request
    ) {
        requireMutableStore(operatorId, storeId);
        requirePublicationRequest(request);
        return execute(operatorId, "MENU_PUBLICATION", key,
                MenuCommandFingerprint.of("PUBLICATION", storeId, menuId, request), () -> {
                    Menu menu = lockedMenu(storeId, menuId);
                    Instant now = databaseClock.now();
                    MenuVersion version;
                    MenuPublicationEventType type;
                    Instant effectiveAt;
                    Instant confirmedAt;
                    if (request.mode() == MenuPublicationMode.IMMEDIATE) {
                        version = menu.publish(now);
                        type = MenuPublicationEventType.PUBLISHED_IMMEDIATELY;
                        effectiveAt = now;
                        confirmedAt = now;
                    } else {
                        version = menu.schedule(request.effectiveAt(), now);
                        type = MenuPublicationEventType.PUBLICATION_SCHEDULED;
                        effectiveAt = request.effectiveAt();
                        confirmedAt = null;
                    }
                    menuRepository.saveAndFlush(menu);
                    record(menu, version.getVersionNumber(), type, operatorId,
                            now, effectiveAt, confirmedAt);
                    return success(HttpStatus.OK, menu);
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuCommandResult cancelPublication(
            long operatorId,
            long storeId,
            long menuId,
            IdempotencyKey key
    ) {
        requireMutableStore(operatorId, storeId);
        return execute(operatorId, "MENU_PUBLICATION_CANCEL", key,
                MenuCommandFingerprint.of("PUBLICATION_CANCEL", storeId, menuId, null), () -> {
                    Menu menu = lockedMenu(storeId, menuId);
                    Instant now = databaseClock.now();
                    MenuVersion cancelled = menu.cancelSchedule(now);
                    menuRepository.saveAndFlush(menu);
                    record(menu, cancelled.getVersionNumber(),
                            MenuPublicationEventType.SCHEDULE_CANCELLED,
                            operatorId, now, cancelled.getEffectiveAt(), now);
                    return success(HttpStatus.OK, menu);
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuCommandResult changeVisibility(
            long operatorId,
            long storeId,
            long menuId,
            IdempotencyKey key,
            MenuVisibilityRequest request
    ) {
        requireMutableStore(operatorId, storeId);
        return execute(operatorId, "MENU_VISIBILITY", key,
                MenuCommandFingerprint.of("VISIBILITY", storeId, menuId, request), () -> {
                    Menu menu = lockedMenu(storeId, menuId);
                    Instant now = databaseClock.now();
                    menu.changeVisibility(request.visibility());
                    menuRepository.saveAndFlush(menu);
                    record(menu, menu.getPublishedVersionNumber(),
                            MenuPublicationEventType.VISIBILITY_CHANGED,
                            operatorId, now, null, now);
                    return success(HttpStatus.OK, menu);
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuCommandResult changeSellingStatus(
            long operatorId,
            long storeId,
            long menuId,
            IdempotencyKey key,
            MenuSellingStatusRequest request
    ) {
        requireMutableStore(operatorId, storeId);
        return execute(operatorId, "MENU_SELLING_STATUS", key,
                MenuCommandFingerprint.of("SELLING_STATUS", storeId, menuId, request), () -> {
                    Menu menu = lockedMenu(storeId, menuId);
                    Instant now = databaseClock.now();
                    menu.changeSellingStatus(request.sellingStatus());
                    menuRepository.saveAndFlush(menu);
                    record(menu, menu.getPublishedVersionNumber(),
                            MenuPublicationEventType.SELLING_STATUS_CHANGED,
                            operatorId, now, null, now);
                    return success(HttpStatus.OK, menu);
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuCommandResult retire(
            long operatorId,
            long storeId,
            long menuId,
            IdempotencyKey key
    ) {
        requireMutableStore(operatorId, storeId);
        return execute(operatorId, "MENU_RETIREMENT", key,
                MenuCommandFingerprint.of("RETIREMENT", storeId, menuId, null), () -> {
                    Menu menu = lockedMenu(storeId, menuId);
                    Instant now = databaseClock.now();
                    menu.retire(now);
                    menuRepository.saveAndFlush(menu);
                    record(menu, null, MenuPublicationEventType.RETIRED,
                            operatorId, now, null, now);
                    return success(HttpStatus.OK, menu);
                });
    }

    private StoreManagementView requireMutableStore(long operatorId, long storeId) {
        StoreManagementView store =
                storeService.requireManagementAuthority(operatorId, storeId);
        if (store.verificationStatus() != VerificationStatus.APPROVED) {
            throw new ServiceException(StoreErrorCode.VERIFICATION_STATE_CONFLICT);
        }
        if (store.operationStatus() == OperationStatus.CLOSED) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
        return store;
    }

    private Menu lockedMenu(long storeId, long menuId) {
        Menu menu = menuRepository.findByIdForUpdate(menuId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.MENU_NOT_FOUND));
        return MenuQueryService.loadForStore(menu, storeId);
    }

    private MenuCommandResult execute(
            long operatorId,
            String commandType,
            IdempotencyKey key,
            String fingerprint,
            Supplier<BusinessResult<ManagedMenuResponse>> work
    ) {
        IdempotentOutcome outcome = idempotencyExecutor.execute(
                new IdempotencyCommand(
                        PRINCIPAL_NAMESPACE, operatorId, commandType,
                        key.value(), fingerprint),
                work);
        ManagedMenuResponse response =
                objectMapper.treeToValue(outcome.data(), ManagedMenuResponse.class);
        return new MenuCommandResult(outcome.httpStatus(), response);
    }

    private static BusinessResult<ManagedMenuResponse> success(
            HttpStatus status,
            Menu menu
    ) {
        return new BusinessResult<>(
                status.value(),
                SUCCESS_RESPONSE_CODE,
                RESOURCE_TYPE,
                String.valueOf(menu.getId()),
                ManagedMenuResponse.from(menu));
    }

    private void record(
            Menu menu,
            Integer versionNumber,
            MenuPublicationEventType type,
            Long operatorId,
            Instant commandedAt,
            Instant effectiveAt,
            Instant confirmedAt
    ) {
        eventRepository.save(MenuPublicationEvent.record(
                menu.getId(), versionNumber, type, operatorId,
                commandedAt, effectiveAt, confirmedAt));
    }

    private static void requirePublicationRequest(MenuPublicationRequest request) {
        if (request == null || request.mode() == null
                || (request.mode() == MenuPublicationMode.IMMEDIATE
                && request.effectiveAt() != null)
                || (request.mode() == MenuPublicationMode.SCHEDULED
                && request.effectiveAt() == null)) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}
