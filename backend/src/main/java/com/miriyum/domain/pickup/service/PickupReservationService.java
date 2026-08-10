package com.miriyum.domain.pickup.service;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireResult;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquiredItem;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireSelection;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityDateQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreResult;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.domain.pickup.dto.request.PickupMenuSelectionRequest;
import com.miriyum.domain.pickup.dto.request.PickupCancellationRequest;
import com.miriyum.domain.pickup.dto.request.PickupReservationCreateRequest;
import com.miriyum.domain.pickup.dto.response.PickupReservationItemResponse;
import com.miriyum.domain.pickup.dto.response.PickupReservationResponse;
import com.miriyum.domain.pickup.entity.PickupItemSnapshot;
import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.entity.PickupReservationItem;
import com.miriyum.domain.pickup.entity.PickupStatus;
import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.domain.pickup.repository.PickupReservationRepository;
import com.miriyum.domain.store.dto.contract.StorePickupTransactionEligibility;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.dto.MenuTransactionEligibility;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
public class PickupReservationService {

    private static final String COMMAND_TYPE = "PICKUP_CREATE";

    private final StoreTransactionEligibilityService storeEligibilityService;
    private final StoreService storeService;
    private final MenuInventoryTransactionService inventoryService;
    private final PickupReservationRepository repository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ConsumerAccountService consumerAccountService;
    private final PickupIntervalTimePolicy intervalTimePolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PickupReservationService(
            StoreTransactionEligibilityService storeEligibilityService,
            StoreService storeService,
            MenuInventoryTransactionService inventoryService,
            PickupReservationRepository repository,
            IdempotencyExecutor idempotencyExecutor,
            ConsumerAccountService consumerAccountService,
            PickupIntervalTimePolicy intervalTimePolicy,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.storeEligibilityService = storeEligibilityService;
        this.storeService = storeService;
        this.inventoryService = inventoryService;
        this.repository = repository;
        this.idempotencyExecutor = idempotencyExecutor;
        this.consumerAccountService = consumerAccountService;
        this.intervalTimePolicy = intervalTimePolicy;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public PickupCommandResult create(
            long consumerAccountId,
            IdempotencyKey key,
            PickupReservationCreateRequest request
    ) {
        if (consumerAccountId <= 0 || key == null || request == null) {
            throw new IllegalArgumentException("pickup creation arguments are required");
        }
        consumerAccountService.requireActiveAccount(consumerAccountId);
        long storeId = request.storeIdAsLong();
        List<PickupMenuSelectionRequest> selections = normalizeMenuSelections(request);
        IdempotencyCommand command = new IdempotencyCommand(
                "consumer", consumerAccountId, COMMAND_TYPE, key.value(),
                fingerprint(storeId, request, selections));

        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () ->
                createWork(consumerAccountId, storeId, key, request, selections));
        return new PickupCommandResult(outcome.httpStatus(), replayResponse(outcome.data()));
    }

    private static List<PickupMenuSelectionRequest> normalizeMenuSelections(
            PickupReservationCreateRequest request
    ) {
        try {
            return request.normalizedMenuSelections();
        } catch (IllegalArgumentException exception) {
            ServiceException validationFailure = new ServiceException(
                    CommonErrorCode.VALIDATION_FAILED);
            validationFailure.initCause(exception);
            throw validationFailure;
        }
    }

    @Transactional(readOnly = true)
    public PickupReservationResponse getConsumerPickup(
            long consumerAccountId,
            long pickupReservationId
    ) {
        if (consumerAccountId <= 0) {
            throw new IllegalArgumentException("consumerAccountId must be positive");
        }
        consumerAccountService.requireActiveAccount(consumerAccountId);
        if (pickupReservationId <= 0) {
            throw new ServiceException(PickupErrorCode.PICKUP_NOT_FOUND);
        }
        PickupReservation pickup = repository.findByIdAndConsumerAccountId(
                        pickupReservationId, consumerAccountId)
                .orElseThrow(() -> new ServiceException(PickupErrorCode.PICKUP_NOT_FOUND));
        return toResponse(pickup);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public PickupCommandResult cancelByConsumer(
            long consumerAccountId,
            long pickupReservationId,
            IdempotencyKey key,
            PickupCancellationRequest request,
            Instant requestedAt
    ) {
        if (consumerAccountId <= 0 || key == null || request == null || requestedAt == null) {
            throw new IllegalArgumentException("pickup cancellation arguments are required");
        }
        consumerAccountService.requireActiveAccount(consumerAccountId);
        if (pickupReservationId <= 0) {
            throw new ServiceException(PickupErrorCode.PICKUP_NOT_FOUND);
        }
        String reason = normalizeOptionalReason(request.reason());
        IdempotencyCommand command = new IdempotencyCommand(
                "consumer", consumerAccountId, "PICKUP_CANCEL", key.value(),
                RequestFingerprint.of("POST|/api/v1/pickup-reservations/"
                        + pickupReservationId + "/cancellations|"
                        + (reason == null ? "" : reason)));
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () ->
                cancelByConsumerWork(
                        consumerAccountId, pickupReservationId, key, reason, requestedAt));
        return new PickupCommandResult(outcome.httpStatus(), replayResponse(outcome.data()));
    }

    private BusinessResult<PickupReservationResponse> cancelByConsumerWork(
            long consumerAccountId,
            long pickupReservationId,
            IdempotencyKey key,
            String reason,
            Instant requestedAt
    ) {
        PickupReservation pickup = repository.findByIdAndConsumerAccountIdForUpdate(
                        pickupReservationId, consumerAccountId)
                .orElseThrow(() -> new ServiceException(PickupErrorCode.PICKUP_NOT_FOUND));
        if (pickup.getStatus() != PickupStatus.CONFIRMED) {
            throw new ServiceException(PickupErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!requestedAt.isBefore(pickup.getPickupAt())) {
            throw new ServiceException(PickupErrorCode.CANCELLATION_NOT_ALLOWED);
        }

        String restoreOperationId = "pickup-cancel-" + consumerAccountId
                + "-" + key.value();
        pickup.cancelByConsumer(reason, requestedAt);
        MenuInventoryRestoreResult restored = inventoryService.restore(
                new MenuInventoryRestoreCommand(
                        restoreOperationId, pickup.getAcquireOperationId()));
        if (!restoreOperationId.equals(restored.operationId())
                || !pickup.getAcquireOperationId().equals(restored.sourceAcquireOperationId())) {
            throw new IllegalStateException("inventory restore result does not match cancellation");
        }
        PickupReservation saved = repository.saveAndFlush(pickup);
        PickupReservationResponse response = toResponse(saved);
        return new BusinessResult<>(HttpStatus.OK.value(), "SUCCESS",
                "pickup-reservation", response.pickupReservationId(), response);
    }

    private static String normalizeOptionalReason(String reason) {
        if (reason == null) {
            return null;
        }
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 500) {
            throw new IllegalArgumentException("cancellation reason has invalid length");
        }
        return reason;
    }

    private PickupReservationResponse replayResponse(JsonNode payload) {
        PickupReservationResponse value = objectMapper.treeToValue(
                payload, PickupReservationResponse.class);
        JsonNode createdAt = payload.get("createdAt");
        return new PickupReservationResponse(
                value.pickupReservationId(), value.storeId(), value.storeName(),
                value.pickupDate(), value.pickupTime(), value.status(), value.items(),
                value.cancelledBy(), value.cancellationReason(),
                createdAt == null || createdAt.isNull()
                        ? null : OffsetDateTime.parse(createdAt.asString()));
    }

    private BusinessResult<PickupReservationResponse> createWork(
            long consumerAccountId,
            long storeId,
            IdempotencyKey key,
            PickupReservationCreateRequest request,
            List<PickupMenuSelectionRequest> selections
    ) {
        StorePickupTransactionEligibility store = requireStoreEligibility(storeId);
        Map<Long, MenuTransactionEligibility> menus = requireMenus(storeId, selections);
        Instant pickupAt = resolvePickupAt(store.timeZoneId(), request);
        Map<Long, SelectedAvailability> availability = selectAvailability(
                store, request, selections);

        List<MenuInventoryAcquireSelection> acquireSelections = selections.stream()
                .map(selection -> {
                    long menuId = selection.menuIdAsLong();
                    MenuInventoryAvailability item = availability.get(menuId).value();
                    return new MenuInventoryAcquireSelection(
                            menuId, item.serviceDate(), item.startTime(), item.endDate(),
                            item.endTime(), item.inventoryPolicyVersion(), selection.quantity());
                })
                .toList();
        availability.values().stream()
                .map(SelectedAvailability::value)
                .forEach(item -> intervalTimePolicy.requireOpen(
                        store.timeZoneId(), item.endDate(), item.endTime()));
        MenuInventoryAcquireResult acquired = acquire(new MenuInventoryAcquireCommand(
                "pickup-create-" + consumerAccountId + "-" + key.value(),
                acquireSelections));
        Map<Long, MenuInventoryAcquiredItem> acquiredByMenu = acquired.items().stream()
                .collect(Collectors.toMap(
                        MenuInventoryAcquiredItem::menuId, Function.identity(),
                        (first, duplicate) -> {
                            throw new IllegalStateException("duplicate acquired pickup menu");
                        }, LinkedHashMap::new));

        List<PickupItemSnapshot> snapshots = selections.stream()
                .map(selection -> snapshot(selection, menus, availability, acquiredByMenu))
                .toList();
        Instant createdAt = clock.instant();
        PickupReservation reservation = PickupReservation.confirm(
                consumerAccountId, storeId, store.storeName(), store.timeZoneId(),
                request.pickupDate(), request.pickupTime(),
                pickupAt,
                acquired.operationId(), snapshots, createdAt);
        PickupReservation saved = repository.saveAndFlush(reservation);
        if (saved.getId() == null || saved.getId() <= 0) {
            throw new IllegalStateException("saved pickup reservation id is required");
        }
        PickupReservationResponse response = toResponse(saved);
        return new BusinessResult<>(HttpStatus.CREATED.value(), "SUCCESS",
                "pickup-reservation", response.pickupReservationId(), response);
    }

    private StorePickupTransactionEligibility requireStoreEligibility(long storeId) {
        try {
            return storeEligibilityService.requirePickupTransactionEligibility(storeId);
        } catch (ServiceException exception) {
            if (exception.getErrorCode() == StoreErrorCode.STORE_STATE_CONFLICT
                    || exception.getErrorCode() == StoreErrorCode.VERIFICATION_STATE_CONFLICT) {
                throw new ServiceException(PickupErrorCode.TRANSACTION_NOT_ELIGIBLE);
            }
            throw exception;
        }
    }

    private Map<Long, MenuTransactionEligibility> requireMenus(
            long storeId,
            List<PickupMenuSelectionRequest> selections
    ) {
        Map<Long, MenuTransactionEligibility> result = new LinkedHashMap<>();
        for (PickupMenuSelectionRequest selection : selections) {
            long menuId = selection.menuIdAsLong();
            MenuTransactionEligibility menu =
                    storeService.requireMenuTransactionEligibility(storeId, menuId);
            if (!menu.pickupEligible()) {
                throw new ServiceException(StoreErrorCode.MENU_STATE_CONFLICT);
            }
            result.put(menuId, menu);
        }
        return result;
    }

    private Map<Long, SelectedAvailability> selectAvailability(
            StorePickupTransactionEligibility store,
            PickupReservationCreateRequest request,
            List<PickupMenuSelectionRequest> selections
    ) {
        List<Long> menuIds = selections.stream()
                .map(PickupMenuSelectionRequest::menuIdAsLong).toList();
        List<MenuInventoryAvailability> candidates = inventoryService
                .findOnlineAvailabilityByDate(new MenuInventoryAvailabilityDateQuery(
                        menuIds, request.pickupDate()));
        Map<Long, SelectedAvailability> result = new LinkedHashMap<>();
        for (PickupMenuSelectionRequest selection : selections) {
            long menuId = selection.menuIdAsLong();
            List<MenuInventoryAvailability> matches = candidates.stream()
                    .filter(item -> item.menuId() == menuId)
                    .filter(item -> item.serviceDate().equals(request.pickupDate()))
                    .filter(item -> item.startTime().equals(request.pickupTime()))
                    .filter(item -> item.timeZoneId().equals(store.timeZoneId()))
                    .toList();
            if (matches.size() != 1) {
                throw new ServiceException(PickupErrorCode.SLOT_NOT_AVAILABLE);
            }
            MenuInventoryAvailability match = matches.getFirst();
            if (!intervalTimePolicy.isOpen(
                    store.timeZoneId(), match.endDate(), match.endTime())) {
                throw new ServiceException(PickupErrorCode.SLOT_NOT_AVAILABLE);
            }
            if (match.availabilityStatus()
                    != MenuInventoryAvailability.AvailabilityStatus.AVAILABLE
                    || match.availableOnlineQuantity() < selection.quantity()) {
                throw new ServiceException(PickupErrorCode.INSUFFICIENT_QUANTITY);
            }
            result.put(menuId, new SelectedAvailability(match));
        }
        return result;
    }

    private MenuInventoryAcquireResult acquire(MenuInventoryAcquireCommand command) {
        try {
            return inventoryService.acquire(command);
        } catch (ServiceException exception) {
            if (exception.getErrorCode() == MenuHoldErrorCode.INSUFFICIENT_QUANTITY) {
                throw new ServiceException(PickupErrorCode.INSUFFICIENT_QUANTITY);
            }
            throw exception;
        }
    }

    private static PickupItemSnapshot snapshot(
            PickupMenuSelectionRequest selection,
            Map<Long, MenuTransactionEligibility> menus,
            Map<Long, SelectedAvailability> availability,
            Map<Long, MenuInventoryAcquiredItem> acquiredByMenu
    ) {
        long menuId = selection.menuIdAsLong();
        MenuTransactionEligibility menu = menus.get(menuId);
        MenuInventoryAvailability interval = availability.get(menuId).value();
        MenuInventoryAcquiredItem acquired = acquiredByMenu.get(menuId);
        if (acquired == null
                || acquired.inventoryPolicyVersion() != interval.inventoryPolicyVersion()
                || acquired.quantity() != selection.quantity()) {
            throw new IllegalStateException("inventory acquisition result does not match request");
        }
        return new PickupItemSnapshot(
                menuId, acquired.inventoryBucketId(), menu.publishedVersionNumber(),
                menu.menuName(), menu.unitPrice(), acquired.inventoryPolicyVersion(),
                interval.serviceDate(), interval.startTime(), interval.endDate(),
                interval.endTime(), acquired.quantity());
    }

    private Instant resolvePickupAt(
            String timeZoneId,
            PickupReservationCreateRequest request
    ) {
        return intervalTimePolicy.resolveUnambiguousInstant(
                        timeZoneId, request.pickupDate(), request.pickupTime())
                .orElseThrow(() -> new ServiceException(PickupErrorCode.SLOT_NOT_AVAILABLE));
    }

    private static String fingerprint(
            long storeId,
            PickupReservationCreateRequest request,
            List<PickupMenuSelectionRequest> selections
    ) {
        String menuPart = selections.stream()
                .map(item -> item.menuId() + ":" + item.quantity())
                .collect(Collectors.joining(","));
        return RequestFingerprint.of("POST|/api/v1/pickup-reservations|"
                + storeId + "|" + request.pickupDate() + "|"
                + request.pickupTime() + "|" + menuPart);
    }

    static PickupReservationResponse toResponse(PickupReservation reservation) {
        ZoneId zoneId = ZoneId.of(reservation.getTimeZoneIdSnapshot());
        List<PickupReservationItemResponse> items = reservation.getItems().stream()
                .map(PickupReservationService::toItemResponse)
                .toList();
        return new PickupReservationResponse(
                Long.toString(reservation.getId()),
                Long.toString(reservation.getStoreId()),
                reservation.getStoreNameSnapshot(), reservation.getPickupDate(),
                reservation.getPickupTime(), reservation.getStatus(), items,
                reservation.getCancelledBy(), reservation.getCancellationReason(),
                OffsetDateTime.ofInstant(reservation.getCreatedAt(), zoneId));
    }

    private static PickupReservationItemResponse toItemResponse(PickupReservationItem item) {
        return new PickupReservationItemResponse(
                Long.toString(item.getMenuId()), item.getMenuNameSnapshot(),
                item.getUnitPriceSnapshot(), item.getQuantity());
    }

    private record SelectedAvailability(MenuInventoryAvailability value) {
    }
}
