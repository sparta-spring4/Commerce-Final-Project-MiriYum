package com.miriyum.domain.pickup.service;

import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreResult;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.domain.pickup.dto.request.PickupStoreSearchRequest;
import com.miriyum.domain.pickup.dto.request.StorePickupCancellationRequest;
import com.miriyum.domain.pickup.dto.response.PickupReservationPageResponse;
import com.miriyum.domain.pickup.dto.response.PickupReservationResponse;
import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.domain.pickup.repository.PickupReservationRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PickupStoreManagementService {

    private final StoreService storeService;
    private final MenuInventoryTransactionService inventoryService;
    private final PickupReservationRepository repository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PickupStoreManagementService(
            StoreService storeService,
            MenuInventoryTransactionService inventoryService,
            PickupReservationRepository repository,
            IdempotencyExecutor idempotencyExecutor,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.storeService = storeService;
        this.inventoryService = inventoryService;
        this.repository = repository;
        this.idempotencyExecutor = idempotencyExecutor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PickupReservationPageResponse list(
            long operatorAccountId,
            long storeId,
            PickupStoreSearchRequest request
    ) {
        requireArguments(operatorAccountId, storeId, request);
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        Sort sort = Sort.by(request.order().sortOrders());
        Pageable pageable = PageRequest.of(request.page(), request.size(), sort);
        Page<PickupReservation> pickups = findPickups(storeId, request, pageable);
        List<Long> pickupIds = pickups.getContent().stream()
                .map(PickupReservation::getId)
                .toList();
        Map<Long, PickupReservation> pickupsWithItems = pickupIds.isEmpty()
                ? Map.of()
                : repository.findAllWithItemsByIdIn(pickupIds).stream()
                        .collect(Collectors.toMap(
                                PickupReservation::getId,
                                Function.identity()));
        List<PickupReservationResponse> responses = pickupIds.stream()
                .map(id -> requireFetchedPickup(pickupsWithItems, id))
                .map(PickupReservationService::toResponse)
                .toList();
        return PickupReservationPageResponse.from(
                new PageImpl<>(responses, pageable, pickups.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public PickupReservationResponse getDetail(
            long operatorAccountId,
            long storeId,
            long pickupReservationId
    ) {
        requireArguments(operatorAccountId, storeId, new Object());
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        return PickupReservationService.toResponse(findStorePickup(
                storeId, pickupReservationId, false));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public PickupCommandResult cancel(
            long operatorAccountId,
            long storeId,
            long pickupReservationId,
            IdempotencyKey key,
            StorePickupCancellationRequest request
    ) {
        requireArguments(operatorAccountId, storeId, request);
        if (key == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        String reason = requireReason(request.reason());
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        IdempotencyCommand command = command(
                operatorAccountId, "PICKUP_STORE_CANCEL", key,
                "POST|/api/v1/store-operator/stores/" + storeId
                        + "/pickup-reservations/" + pickupReservationId
                        + "/cancellations|" + reason);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            PickupReservation pickup = findStorePickup(storeId, pickupReservationId, true);
            pickup.cancelByStoreOperator(reason, clock.instant());
            String operationId = "pickup-store-cancel-" + operatorAccountId
                    + "-" + key.value();
            MenuInventoryRestoreResult restored = inventoryService.restore(
                    new MenuInventoryRestoreCommand(
                            operationId, pickup.getAcquireOperationId()));
            requireMatchingRestore(pickup, operationId, restored);
            return success(repository.saveAndFlush(pickup));
        });
        return result(outcome);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public PickupCommandResult fulfill(
            long operatorAccountId,
            long storeId,
            long pickupReservationId,
            IdempotencyKey key
    ) {
        requireArguments(operatorAccountId, storeId, new Object());
        if (key == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        IdempotencyCommand command = command(
                operatorAccountId, "PICKUP_FULFILL", key,
                "POST|/api/v1/store-operator/stores/" + storeId
                        + "/pickup-reservations/" + pickupReservationId
                        + "/fulfillments");
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            PickupReservation pickup = findStorePickup(storeId, pickupReservationId, true);
            pickup.pickUp(clock.instant());
            return success(repository.saveAndFlush(pickup));
        });
        return result(outcome);
    }

    private Page<PickupReservation> findPickups(
            long storeId,
            PickupStoreSearchRequest request,
            Pageable pageable
    ) {
        if (request.pickupDate() != null && request.status() != null) {
            return repository.findAllByStoreIdAndPickupDateAndStatus(
                    storeId, request.pickupDate(), request.status(), pageable);
        }
        if (request.pickupDate() != null) {
            return repository.findAllByStoreIdAndPickupDate(
                    storeId, request.pickupDate(), pageable);
        }
        if (request.status() != null) {
            return repository.findAllByStoreIdAndStatus(storeId, request.status(), pageable);
        }
        return repository.findAllByStoreId(storeId, pageable);
    }

    private PickupReservation findStorePickup(
            long storeId,
            long pickupReservationId,
            boolean forUpdate
    ) {
        if (pickupReservationId <= 0) {
            throw new ServiceException(PickupErrorCode.PICKUP_NOT_FOUND);
        }
        return (forUpdate
                ? repository.findByIdAndStoreIdForUpdate(pickupReservationId, storeId)
                : repository.findByIdAndStoreId(pickupReservationId, storeId))
                .orElseThrow(() -> new ServiceException(PickupErrorCode.PICKUP_NOT_FOUND));
    }

    private static PickupReservation requireFetchedPickup(
            Map<Long, PickupReservation> pickups,
            Long pickupId
    ) {
        PickupReservation pickup = pickups.get(pickupId);
        if (pickup == null) {
            throw new IllegalStateException("paged pickup could not be fetched with items");
        }
        return pickup;
    }

    private static IdempotencyCommand command(
            long operatorAccountId,
            String type,
            IdempotencyKey key,
            String canonicalInput
    ) {
        return new IdempotencyCommand(
                "store-operator", operatorAccountId, type, key.value(),
                RequestFingerprint.of(canonicalInput));
    }

    private BusinessResult<PickupReservationResponse> success(PickupReservation pickup) {
        PickupReservationResponse response = PickupReservationService.toResponse(pickup);
        return new BusinessResult<>(HttpStatus.OK.value(), "SUCCESS",
                "pickup-reservation", response.pickupReservationId(), response);
    }

    private PickupCommandResult result(IdempotentOutcome outcome) {
        PickupReservationResponse value = objectMapper.treeToValue(
                outcome.data(), PickupReservationResponse.class);
        JsonNode createdAt = outcome.data().get("createdAt");
        PickupReservationResponse response = new PickupReservationResponse(
                value.pickupReservationId(), value.storeId(), value.storeName(),
                value.pickupDate(), value.pickupTime(), value.status(), value.items(),
                value.cancelledBy(), value.cancellationReason(),
                createdAt == null || createdAt.isNull()
                        ? null : OffsetDateTime.parse(createdAt.asString()));
        return new PickupCommandResult(outcome.httpStatus(), response);
    }

    private static void requireMatchingRestore(
            PickupReservation pickup,
            String operationId,
            MenuInventoryRestoreResult restored
    ) {
        if (!operationId.equals(restored.operationId())
                || !pickup.getAcquireOperationId().equals(restored.sourceAcquireOperationId())) {
            throw new IllegalStateException("inventory restore result does not match cancellation");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("store cancellation reason is required");
        }
        return reason;
    }

    private static void requireArguments(long operatorAccountId, long storeId, Object request) {
        if (operatorAccountId <= 0 || storeId <= 0 || request == null) {
            throw new IllegalArgumentException("store pickup arguments are required");
        }
    }
}
