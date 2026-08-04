package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketCreateCommand;
import com.miriyum.domain.menuhold.inventory.dto.InventoryPolicyChange;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryPolicyAudit;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryPolicyAuditRepository;
import com.miriyum.domain.store.core.service.StoreScheduleAuthority;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.menu.dto.ManagedMenuResponse;
import com.miriyum.domain.store.menu.dto.MenuTransactionEligibility;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.service.MenuQueryService;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MenuInventoryAdminCommandServiceTest {

    @Mock StoreService storeService;
    @Mock MenuQueryService menuQueryService;
    @Mock MenuInventoryBucketRepository bucketRepository;
    @Mock MenuInventoryPolicyAuditRepository auditRepository;
    @Mock IdempotencyExecutor idempotencyExecutor;
    @Mock MenuInventoryPolicyService policyService;

    @Test
    void createsVersionOneAndAuditInsideTheIdempotentCommand() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<MenuInventoryBucket> savedBucket = new AtomicReference<>();
        AtomicReference<MenuInventoryPolicyAudit> savedAudit = new AtomicReference<>();
        given(menuQueryService.get(7L, 3L, 11L)).willReturn(new ManagedMenuResponse(
                "11", "3", MenuVisibility.VISIBLE, MenuSellingStatus.SELLING,
                false, null, null, null));
        given(storeService.requireSchedulePublicationAuthority(7L, 3L))
                .willReturn(new StoreScheduleAuthority(3L, "Asia/Seoul"));
        given(storeService.requireMenuTransactionEligibility(3L, 11L))
                .willReturn(new MenuTransactionEligibility(3L, 11L, 2, true, false));
        given(bucketRepository.saveAndFlush(any())).willAnswer(invocation -> {
            MenuInventoryBucket bucket = invocation.getArgument(0);
            ReflectionTestUtils.setField(bucket, "id", 41L);
            savedBucket.set(bucket);
            return bucket;
        });
        given(auditRepository.saveAndFlush(any())).willAnswer(invocation -> {
            MenuInventoryPolicyAudit audit = invocation.getArgument(0);
            savedAudit.set(audit);
            return audit;
        });
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(),
                    mapper.valueToTree(result.data()));
        });
        MenuInventoryAdminCommandService service = new MenuInventoryAdminCommandService(
                storeService, menuQueryService, bucketRepository, auditRepository,
                policyService, idempotencyExecutor, mapper);

        var result = service.create(
                7L, 3L,
                IdempotencyKey.parse("123e4567-e89b-12d3-a456-426614174000"),
                new InventoryBucketCreateCommand(
                        11L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                        LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                        5, 3, 1, 1, true, InventoryAvailabilityStatus.AVAILABLE));

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.data().policyVersion()).isEqualTo(1L);
        assertThat(savedBucket.get().getTimeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(savedAudit.get().getPreviousBucketId()).isNull();
    }

    @Test
    void updatesTheCurrentPolicyThroughTheIdempotentCommand() {
        ObjectMapper mapper = new ObjectMapper();
        MenuInventoryBucket current = MenuInventoryBucket.create(
                11L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                "Asia/Seoul", 1L, 5, 3, 1, 1, true);
        ReflectionTestUtils.setField(current, "id", 41L);
        MenuInventoryBucket next = current.publishNextPolicy(
                6, 4, 1, 1, true, InventoryAvailabilityStatus.AVAILABLE);
        ReflectionTestUtils.setField(next, "id", 42L);
        given(menuQueryService.get(7L, 3L, 11L)).willReturn(new ManagedMenuResponse(
                "11", "3", MenuVisibility.VISIBLE, MenuSellingStatus.SELLING,
                false, null, null, null));
        given(storeService.requireMenuTransactionEligibility(3L, 11L))
                .willReturn(new MenuTransactionEligibility(3L, 11L, 2, true, false));
        given(bucketRepository.findById(41L)).willReturn(java.util.Optional.of(current));
        given(policyService.publishNextPolicy(
                any(Long.class), any(), any(), any(Long.class), any()))
                .willReturn(next);
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(), mapper.valueToTree(result.data()));
        });
        MenuInventoryAdminCommandService service = new MenuInventoryAdminCommandService(
                storeService, menuQueryService, bucketRepository, auditRepository,
                policyService, idempotencyExecutor, mapper);

        var result = service.update(
                7L, 3L, 41L,
                IdempotencyKey.parse("123e4567-e89b-12d3-a456-426614174001"),
                new InventoryPolicyChange(
                        6, 4, 1, 1, true, InventoryAvailabilityStatus.AVAILABLE));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.data().inventoryBucketId()).isEqualTo(42L);
        assertThat(result.data().policyVersion()).isEqualTo(2L);
    }
}
