package com.miriyum.domain.menu.service;

import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuItemResponse;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuReplaceRequest;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuSettingResponse;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.entity.RepresentativeMenuAudit;
import com.miriyum.domain.menu.entity.RepresentativeMenuSetting;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVersionStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.menu.enums.RepresentativeMenuAuditActorType;
import com.miriyum.domain.menu.enums.RepresentativeMenuAuditEventType;
import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.menu.repository.RepresentativeMenuAuditRepository;
import com.miriyum.domain.menu.repository.RepresentativeMenuSettingRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class RepresentativeMenuService {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String COMMAND_TYPE = "REPRESENTATIVE_MENU_REPLACE";
    private static final String RESOURCE_TYPE = "REPRESENTATIVE_MENU_SETTING";
    private static final String SUCCESS_RESPONSE_CODE = "SUCCESS";

    private final StoreService storeService;
    private final RepresentativeMenuSettingRepository settingRepository;
    private final RepresentativeMenuAuditRepository auditRepository;
    private final MenuRepository menuRepository;
    private final MenuDatabaseClock databaseClock;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public RepresentativeMenuSettingResponse get(long operatorId, long storeId) {
        storeService.requireManagementOwnership(operatorId, storeId);
        return settingRepository.findDetailedByStoreId(storeId)
                .map(this::responseForCurrentSetting)
                .orElseGet(RepresentativeMenuSettingResponse::unconfigured);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public RepresentativeMenuCommandResult replace(
            long operatorId,
            long storeId,
            IdempotencyKey key,
            RepresentativeMenuReplaceRequest request
    ) {
        List<Long> orderedMenuIds = validateAndParse(request);
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(
                new IdempotencyCommand(
                        PRINCIPAL_NAMESPACE,
                        operatorId,
                        COMMAND_TYPE,
                        key.value(),
                        RepresentativeMenuCommandFingerprint.of(storeId, request)),
                replaceWork(operatorId, storeId, key, request, orderedMenuIds));
        RepresentativeMenuSettingResponse response = objectMapper.treeToValue(
                outcome.data(), RepresentativeMenuSettingResponse.class);
        return new RepresentativeMenuCommandResult(outcome.httpStatus(), response);
    }

    private Supplier<BusinessResult<RepresentativeMenuSettingResponse>> replaceWork(
            long operatorId,
            long storeId,
            IdempotencyKey key,
            RepresentativeMenuReplaceRequest request,
            List<Long> orderedMenuIds
    ) {
        return () -> {
            settingRepository.ensureExists(storeId);
            RepresentativeMenuSetting locked = lockSetting(storeId);
            if (locked.getVersion() != request.expectedVersion()) {
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }

            List<Long> sortedMenuIds = orderedMenuIds.stream().sorted().toList();
            List<Menu> lockedMenus = menuRepository.findAllByIdForUpdate(sortedMenuIds);
            Map<Long, Menu> menusById = requireEligibleMenus(
                    storeId, sortedMenuIds, lockedMenus);
            long beforeVersion = locked.getVersion();
            RepresentativeMenuSettingStatus beforeStatus = locked.getStatus();

            settingRepository.deleteEntriesForReplacement(storeId);
            RepresentativeMenuSetting replacement = lockSetting(storeId);
            replacement.replace(orderedMenuIds);
            settingRepository.saveAndFlush(replacement);

            List<RepresentativeMenuItemResponse> items = new ArrayList<>();
            for (int index = 0; index < orderedMenuIds.size(); index++) {
                items.add(RepresentativeMenuItemResponse.from(
                        menusById.get(orderedMenuIds.get(index)), index + 1));
            }
            RepresentativeMenuSettingResponse response =
                    new RepresentativeMenuSettingResponse(
                            replacement.getVersion(), replacement.getStatus(), items);
            Instant now = databaseClock.now();
            auditRepository.save(RepresentativeMenuAudit.create(
                    storeId,
                    beforeVersion,
                    replacement.getVersion(),
                    beforeStatus,
                    replacement.getStatus(),
                    RepresentativeMenuAuditActorType.OPERATOR,
                    operatorId,
                    RepresentativeMenuAuditEventType.REPLACED,
                    null,
                    objectMapper.writeValueAsString(orderedMenuIds),
                    key.value(),
                    now));
            return new BusinessResult<>(
                    HttpStatus.OK.value(), SUCCESS_RESPONSE_CODE,
                    RESOURCE_TYPE, String.valueOf(storeId), response);
        };
    }

    private RepresentativeMenuSetting lockSetting(long storeId) {
        return settingRepository.findByStoreIdForUpdate(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
    }

    private Map<Long, Menu> requireEligibleMenus(
            long storeId,
            List<Long> expectedMenuIds,
            List<Menu> menus
    ) {
        if (menus.size() != expectedMenuIds.size()) {
            throw new ServiceException(StoreErrorCode.MENU_NOT_FOUND);
        }
        Map<Long, Menu> byId = new HashMap<>();
        for (Menu menu : menus) {
            if (menu.getStoreId() != storeId || !expectedMenuIds.contains(menu.getId())) {
                throw new ServiceException(StoreErrorCode.MENU_NOT_FOUND);
            }
            if (menu.isRetired()
                    || menu.getPublishedVersionNumber() == null
                    || !hasCurrentPublishedVersion(menu)
                    || menu.getVisibility() != MenuVisibility.VISIBLE
                    || (menu.getSellingStatus() != MenuSellingStatus.SELLING
                    && menu.getSellingStatus() != MenuSellingStatus.SOLD_OUT)) {
                throw new ServiceException(StoreErrorCode.MENU_STATE_CONFLICT);
            }
            byId.put(menu.getId(), menu);
        }
        if (byId.size() != expectedMenuIds.size()) {
            throw new ServiceException(StoreErrorCode.MENU_NOT_FOUND);
        }
        return byId;
    }

    private RepresentativeMenuSettingResponse responseForCurrentSetting(
            RepresentativeMenuSetting setting
    ) {
        List<Long> orderedIds = setting.orderedMenuIds();
        Map<Long, Menu> byId = new HashMap<>();
        menuRepository.findAllManagedByIds(orderedIds)
                .forEach(menu -> byId.put(menu.getId(), menu));
        List<RepresentativeMenuItemResponse> items = new ArrayList<>();
        for (int index = 0; index < orderedIds.size(); index++) {
            Menu menu = byId.get(orderedIds.get(index));
            if (menu != null && isPubliclyEligible(menu)) {
                items.add(RepresentativeMenuItemResponse.from(menu, index + 1));
            }
        }
        return new RepresentativeMenuSettingResponse(
                setting.getVersion(), setting.getStatus(), items);
    }

    private static boolean isPubliclyEligible(Menu menu) {
        return !menu.isRetired()
                && menu.getPublishedVersionNumber() != null
                && hasCurrentPublishedVersion(menu)
                && menu.getVisibility() == MenuVisibility.VISIBLE
                && (menu.getSellingStatus() == MenuSellingStatus.SELLING
                || menu.getSellingStatus() == MenuSellingStatus.SOLD_OUT);
    }

    private static List<Long> validateAndParse(RepresentativeMenuReplaceRequest request) {
        if (request == null || request.expectedVersion() == null
                || request.expectedVersion() < 0
                || request.menuIds() == null
                || request.menuIds().size() < 3
                || request.menuIds().size() > 5
                || new HashSet<>(request.menuIds()).size() != request.menuIds().size()) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        try {
            List<Long> ids = request.menuIds().stream().map(Long::parseLong).toList();
            boolean nonCanonical = java.util.stream.IntStream.range(0, ids.size())
                    .anyMatch(index -> !String.valueOf(ids.get(index))
                            .equals(request.menuIds().get(index)));
            if (ids.stream().anyMatch(id -> id <= 0)
                    || nonCanonical
                    || new HashSet<>(ids).size() != ids.size()) {
                throw new NumberFormatException("non-positive menu id");
            }
            return ids;
        } catch (NumberFormatException exception) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private static boolean hasCurrentPublishedVersion(Menu menu) {
        return menu.getVersions().stream()
                .anyMatch(version -> version.getVersionNumber()
                        == menu.getPublishedVersionNumber()
                        && version.getStatus() == MenuVersionStatus.PUBLISHED);
    }
}
