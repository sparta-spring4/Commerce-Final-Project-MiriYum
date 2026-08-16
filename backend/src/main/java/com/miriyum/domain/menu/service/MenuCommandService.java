package com.miriyum.domain.menu.service;

import com.miriyum.domain.store.service.StoreMenuAuthority;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.menu.dto.storeoperator.ManagedMenuResponse;
import com.miriyum.domain.menu.dto.storeoperator.MenuContentRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuChangeReasonRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuPublicationRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuSellingStatusRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuVisibilityRequest;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.entity.MenuPublicationEvent;
import com.miriyum.domain.menu.entity.MenuVersion;
import com.miriyum.domain.menu.entity.RepresentativeMenuSetting;
import com.miriyum.domain.menu.enums.MenuPublicationEventType;
import com.miriyum.domain.menu.enums.MenuPublicationMode;
import com.miriyum.domain.menu.enums.MenuAuditActorType;
import com.miriyum.domain.menu.enums.MenuAuditOutcome;
import com.miriyum.domain.menu.enums.MenuImpactCheckStatus;
import com.miriyum.domain.menu.enums.MenuRecoveryResult;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.menu.model.MenuAuditRecord;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.repository.MenuPublicationEventRepository;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
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
    private static final String ALL_CONTENT_FIELDS = String.join(",",
            "NAME", "DESCRIPTION", "PRICE", "REPRESENTATIVE",
            "PRIMARY_CATEGORY", "SECONDARY_CATEGORIES", "LOCAL_TAGS",
            "HOLD_SELECTION_ALLOWED", "PICKUP_SELECTION_ALLOWED",
            "ALLERGEN_INFORMATION", "ORIGIN_INFORMATION", "ALCOHOLIC");

    private final StoreService storeService;
    private final MenuRepository menuRepository;
    private final MenuPublicationEventRepository eventRepository;
    private final MenuContentPolicy contentPolicy;
    private final MenuDatabaseClock databaseClock;
    private final RepresentativeMenuService representativeMenuService;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuCommandResult create(
            long operatorId,
            long storeId,
            IdempotencyKey key,
            MenuContentRequest request
    ) {
        storeService.requireManagementOwnership(operatorId, storeId);
        return execute(operatorId, "MENU_CREATE", key,
                MenuCommandFingerprint.of("CREATE", storeId, 0, request), () -> {
                    StoreMenuAuthority store =
                            storeService.requireMenuMutationAuthority(operatorId, storeId);
                    MenuContent content = contentPolicy.validateAndNormalize(request, store);
                    Instant now = databaseClock.now();
                    Menu menu = menuRepository.saveAndFlush(
                            Menu.create(storeId, content, operatorId, now));
                    recordOperator(menu, menu.getDraftVersionNumber(),
                            MenuPublicationEventType.DRAFT_CREATED, operatorId,
                            now, null, now, key.value(), null,
                            null, menu.getDraftVersionNumber(), ALL_CONTENT_FIELDS,
                            MenuVisibility.HIDDEN, MenuVisibility.HIDDEN,
                            MenuSellingStatus.PAUSED, MenuSellingStatus.PAUSED,
                            MenuImpactCheckStatus.NOT_APPLICABLE,
                            MenuRecoveryResult.NOT_APPLICABLE);
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
        storeService.requireManagementOwnership(operatorId, storeId);
        return execute(operatorId, "MENU_UPDATE", key,
                MenuCommandFingerprint.of("UPDATE", storeId, menuId, request), () -> {
                    StoreMenuAuthority store =
                            storeService.requireMenuMutationAuthority(operatorId, storeId);
                    MenuContent content = contentPolicy.validateAndNormalize(request, store);
                    Menu menu = lockedMenu(storeId, menuId);
                    MenuVersion previous = editableBaseline(menu);
                    Instant now = databaseClock.now();
                    MenuVersion draft = menu.appendDraft(content, operatorId, now);
                    Menu saved = menuRepository.saveAndFlush(menu);
                    recordOperator(saved, draft.getVersionNumber(),
                            MenuPublicationEventType.DRAFT_UPDATED, operatorId,
                            now, null, now, key.value(), null,
                            previous == null ? null : previous.getVersionNumber(),
                            draft.getVersionNumber(), changedFields(previous, draft),
                            saved.getVisibility(), saved.getVisibility(),
                            saved.getSellingStatus(), saved.getSellingStatus(),
                            MenuImpactCheckStatus.NOT_APPLICABLE,
                            MenuRecoveryResult.NOT_APPLICABLE);
                    return success(HttpStatus.OK, saved);
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
        storeService.requireManagementOwnership(operatorId, storeId);
        requirePublicationRequest(request);
        return execute(operatorId, "MENU_PUBLICATION", key,
                MenuCommandFingerprint.of("PUBLICATION", storeId, menuId, request), () -> {
                    storeService.requireMenuMutationAuthority(operatorId, storeId);
                    Menu menu = lockedMenu(storeId, menuId);
                    Instant now = databaseClock.now();
                    Integer previousVersionNumber = menu.getPublishedVersionNumber();
                    MenuVisibility previousVisibility = menu.getVisibility();
                    MenuSellingStatus previousSellingStatus = menu.getSellingStatus();
                    MenuVersion version;
                    MenuPublicationEventType type;
                    Instant effectiveAt;
                    Instant confirmedAt;
                    if (request.mode() == MenuPublicationMode.IMMEDIATE) {
                        version = menu.publish(now);
                        type = MenuPublicationEventType.PUBLISHED_IMMEDIATELY;
                        effectiveAt = now;
                        confirmedAt = now;
                        publishSemanticIndexChange(menu.getId(), now);
                    } else {
                        version = menu.schedule(request.effectiveAt(), now);
                        type = MenuPublicationEventType.PUBLICATION_SCHEDULED;
                        effectiveAt = request.effectiveAt();
                        confirmedAt = null;
                    }
                    menuRepository.saveAndFlush(menu);
                    recordOperator(menu, version.getVersionNumber(), type, operatorId,
                            now, effectiveAt, confirmedAt, key.value(), request.changeReason(),
                            previousVersionNumber, version.getVersionNumber(), "VERSION_STATUS",
                            previousVisibility, menu.getVisibility(),
                            previousSellingStatus, menu.getSellingStatus(),
                            MenuImpactCheckStatus.NOT_EVALUATED,
                            MenuRecoveryResult.NOT_EVALUATED);
                    return success(HttpStatus.OK, menu);
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuCommandResult cancelPublication(
            long operatorId,
            long storeId,
            long menuId,
            IdempotencyKey key,
            MenuChangeReasonRequest request
    ) {
        storeService.requireManagementOwnership(operatorId, storeId);
        return execute(operatorId, "MENU_PUBLICATION_CANCEL", key,
                MenuCommandFingerprint.of("PUBLICATION_CANCEL", storeId, menuId, request), () -> {
                    storeService.requireMenuMutationAuthority(operatorId, storeId);
                    Menu menu = lockedMenu(storeId, menuId);
                    Instant now = databaseClock.now();
                    Integer scheduledVersionNumber = menu.getScheduledVersionNumber();
                    Instant scheduledEffectiveAt = scheduledVersionNumber == null
                            ? null
                            : menu.getVersions().stream()
                                    .filter(version -> Objects.equals(
                                            version.getVersionNumber(),
                                            scheduledVersionNumber))
                                    .map(MenuVersion::getEffectiveAt)
                                    .findFirst()
                                    .orElse(null);
                    MenuVersion cancelled = menu.cancelSchedule(now);
                    menuRepository.saveAndFlush(menu);
                    recordOperator(menu, cancelled.getVersionNumber(),
                            MenuPublicationEventType.SCHEDULE_CANCELLED, operatorId,
                            now, scheduledEffectiveAt, now, key.value(),
                            request.changeReason(), cancelled.getVersionNumber(),
                            cancelled.getVersionNumber(),
                            "VERSION_STATUS,EFFECTIVE_AT",
                            menu.getVisibility(), menu.getVisibility(),
                            menu.getSellingStatus(), menu.getSellingStatus(),
                            MenuImpactCheckStatus.NOT_APPLICABLE,
                            MenuRecoveryResult.NOT_APPLICABLE);
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
        storeService.requireManagementOwnership(operatorId, storeId);
        return execute(operatorId, "MENU_VISIBILITY", key,
                MenuCommandFingerprint.of("VISIBILITY", storeId, menuId, request), () -> {
                    storeService.requireMenuMutationAuthority(operatorId, storeId);
                    RepresentativeMenuSetting representativeSetting =
                            representativeMenuService.lockSetting(storeId);
                    Menu menu = lockedMenu(storeId, menuId);
                    Instant now = databaseClock.now();
                    MenuVisibility previousVisibility = menu.getVisibility();
                    menu.changeVisibility(request.visibility());
                    menuRepository.saveAndFlush(menu);
                    if (menu.getVisibility() == MenuVisibility.HIDDEN) {
                        representativeMenuService.autoRemoveLocked(
                                representativeSetting, menuId, now, key.value());
                    }
                    recordOperator(menu, menu.getPublishedVersionNumber(),
                            MenuPublicationEventType.VISIBILITY_CHANGED, operatorId,
                            now, now, now, key.value(), request.changeReason(),
                            menu.getPublishedVersionNumber(), menu.getPublishedVersionNumber(),
                            "VISIBILITY", previousVisibility, menu.getVisibility(),
                            menu.getSellingStatus(), menu.getSellingStatus(),
                            MenuImpactCheckStatus.NOT_EVALUATED,
                            MenuRecoveryResult.NOT_EVALUATED);
                    publishSemanticIndexChange(menu.getId(), now);
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
        storeService.requireManagementOwnership(operatorId, storeId);
        return execute(operatorId, "MENU_SELLING_STATUS", key,
                MenuCommandFingerprint.of("SELLING_STATUS", storeId, menuId, request), () -> {
                    storeService.requireMenuMutationAuthority(operatorId, storeId);
                    RepresentativeMenuSetting representativeSetting =
                            representativeMenuService.lockSetting(storeId);
                    Menu menu = lockedMenu(storeId, menuId);
                    Instant now = databaseClock.now();
                    MenuSellingStatus previousSellingStatus = menu.getSellingStatus();
                    menu.changeSellingStatus(request.sellingStatus());
                    menuRepository.saveAndFlush(menu);
                    if (menu.getSellingStatus() == MenuSellingStatus.PAUSED) {
                        representativeMenuService.autoRemoveLocked(
                                representativeSetting, menuId, now, key.value());
                    }
                    recordOperator(menu, menu.getPublishedVersionNumber(),
                            MenuPublicationEventType.SELLING_STATUS_CHANGED, operatorId,
                            now, now, now, key.value(), request.changeReason(),
                            menu.getPublishedVersionNumber(), menu.getPublishedVersionNumber(),
                            "SELLING_STATUS", menu.getVisibility(), menu.getVisibility(),
                            previousSellingStatus, menu.getSellingStatus(),
                            MenuImpactCheckStatus.NOT_EVALUATED,
                            MenuRecoveryResult.NOT_EVALUATED);
                    publishSemanticIndexChange(menu.getId(), now);
                    return success(HttpStatus.OK, menu);
                });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public MenuCommandResult retire(
            long operatorId,
            long storeId,
            long menuId,
            IdempotencyKey key,
            MenuChangeReasonRequest request
    ) {
        storeService.requireManagementOwnership(operatorId, storeId);
        return execute(operatorId, "MENU_RETIREMENT", key,
                MenuCommandFingerprint.of("RETIREMENT", storeId, menuId, request), () -> {
                    storeService.requireMenuMutationAuthority(operatorId, storeId);
                    RepresentativeMenuSetting representativeSetting =
                            representativeMenuService.lockSetting(storeId);
                    Menu menu = lockedMenu(storeId, menuId);
                    Instant now = databaseClock.now();
                    Integer previousVersion = menu.getPublishedVersionNumber();
                    MenuVisibility previousVisibility = menu.getVisibility();
                    MenuSellingStatus previousSellingStatus = menu.getSellingStatus();
                    menu.retire(now);
                    menuRepository.saveAndFlush(menu);
                    representativeMenuService.autoRemoveLocked(
                            representativeSetting, menuId, now, key.value());
                    recordOperator(menu, null, MenuPublicationEventType.RETIRED,
                            operatorId, now, now, now, key.value(), request.changeReason(),
                            previousVersion, null,
                            "VERSION_STATUS,VISIBILITY,SELLING_STATUS",
                            previousVisibility, menu.getVisibility(),
                            previousSellingStatus, menu.getSellingStatus(),
                            MenuImpactCheckStatus.NOT_EVALUATED,
                            MenuRecoveryResult.NOT_EVALUATED);
                    publishSemanticIndexChange(menu.getId(), now);
                    return success(HttpStatus.OK, menu);
                });
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

    private void recordOperator(
            Menu menu,
            Integer versionNumber,
            MenuPublicationEventType type,
            long operatorId,
            Instant commandedAt,
            Instant effectiveAt,
            Instant confirmedAt,
            String requestId,
            String changeReason,
            Integer previousVersionNumber,
            Integer newVersionNumber,
            String changedFields,
            MenuVisibility previousVisibility,
            MenuVisibility newVisibility,
            MenuSellingStatus previousSellingStatus,
            MenuSellingStatus newSellingStatus,
            MenuImpactCheckStatus impactCheckStatus,
            MenuRecoveryResult recoveryResult
    ) {
        eventRepository.save(MenuPublicationEvent.record(new MenuAuditRecord(
                menu.getId(), versionNumber, type, MenuAuditActorType.OPERATOR,
                operatorId, commandedAt, effectiveAt, confirmedAt, requestId,
                MenuAuditOutcome.SUCCEEDED, previousVersionNumber, newVersionNumber,
                changedFields, changeReason, previousVisibility, newVisibility,
                previousSellingStatus, newSellingStatus, impactCheckStatus, null,
                recoveryResult)));
    }

    private static MenuVersion editableBaseline(Menu menu) {
        Integer versionNumber = menu.getDraftVersionNumber() != null
                ? menu.getDraftVersionNumber() : menu.getPublishedVersionNumber();
        if (versionNumber == null) {
            return null;
        }
        return menu.getVersions().stream()
                .filter(version -> version.getVersionNumber() == versionNumber)
                .findFirst()
                .orElse(null);
    }

    private static String changedFields(MenuVersion previous, MenuVersion current) {
        if (previous == null) {
            return ALL_CONTENT_FIELDS;
        }
        List<String> changed = new ArrayList<>();
        addChanged(changed, "NAME", previous.getName(), current.getName());
        addChanged(changed, "DESCRIPTION", previous.getDescription(), current.getDescription());
        addChanged(changed, "PRICE", previous.getPrice(), current.getPrice());
        addChanged(changed, "REPRESENTATIVE",
                previous.isRepresentative(), current.isRepresentative());
        addChanged(changed, "PRIMARY_CATEGORY",
                previous.getPrimaryCategoryCode(), current.getPrimaryCategoryCode());
        addChanged(changed, "SECONDARY_CATEGORIES",
                previous.getSecondaryCategoryCodes(), current.getSecondaryCategoryCodes());
        addChanged(changed, "LOCAL_TAGS", previous.getLocalTags(), current.getLocalTags());
        addChanged(changed, "HOLD_SELECTION_ALLOWED",
                previous.isHoldSelectionAllowed(), current.isHoldSelectionAllowed());
        addChanged(changed, "PICKUP_SELECTION_ALLOWED",
                previous.isPickupSelectionAllowed(), current.isPickupSelectionAllowed());
        addChanged(changed, "ALLERGEN_INFORMATION",
                List.of(previous.getAllergenInformationStatus(),
                        previous.getAllergenDisclosures()),
                List.of(current.getAllergenInformationStatus(),
                        current.getAllergenDisclosures()));
        addChanged(changed, "ORIGIN_INFORMATION",
                List.of(previous.getOriginInformationStatus(), previous.getOriginDisclosures()),
                List.of(current.getOriginInformationStatus(), current.getOriginDisclosures()));
        addChanged(changed, "ALCOHOLIC", previous.isAlcoholic(), current.isAlcoholic());
        return String.join(",", changed);
    }

    private static void addChanged(
            List<String> changed,
            String field,
            Object previous,
            Object current
    ) {
        if (!Objects.equals(previous, current)) {
            changed.add(field);
        }
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

    private void publishSemanticIndexChange(long menuId, Instant signaledAt) {
        applicationEventPublisher.publishEvent(new SemanticIndexChanged(menuId, signaledAt));
    }

    /** 메뉴 커밋 후 Search 인덱스를 재검증하게 하는 식별자 전용 신호다. */
    public record SemanticIndexChanged(long menuId, Instant signaledAt) {
        public SemanticIndexChanged(long menuId) {
            this(menuId, Instant.now());
        }
    }
}
