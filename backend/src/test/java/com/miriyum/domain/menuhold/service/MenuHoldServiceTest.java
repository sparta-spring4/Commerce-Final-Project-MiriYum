package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAcquireRequest;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAcquisitionResult;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAllocationResult;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryLedgerRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MenuHoldServiceTest {

    @Mock
    private MenuInventoryBucketRepository bucketRepository;

    @Mock
    private MenuInventoryLedgerRepository ledgerRepository;

    @Test
    void acquiresBucketsInPrimaryKeyOrderAndKeepsMenuMapping() {
        MenuInventoryBucket first = bucket(9L, 1, 3);
        MenuInventoryBucket second = bucket(3L, 2, 0);
        InventoryAcquireRequest request = new InventoryAcquireRequest(
                "reservation:77:create",
                List.of(selection(9L, 2), selection(3L, 2)));
        given(bucketRepository.findBucketId(selection(9L, 2).key())).willReturn(9L);
        given(bucketRepository.findBucketId(selection(3L, 2).key())).willReturn(3L);
        given(bucketRepository.findRequestedAndCurrentForUpdate(List.of(3L, 9L)))
                .willReturn(List.of(second, first));
        given(bucketRepository.decrementIfCurrent(3L, 0L, 2, 0)).willReturn(1);
        given(bucketRepository.decrementIfCurrent(9L, 0L, 1, 1)).willReturn(1);

        MenuInventoryService service = service();

        List<InventoryAcquisitionResult> results = service.acquireInventory(request);

        assertThat(results).extracting(InventoryAcquisitionResult::inventoryBucketId)
                .containsExactly(3L, 9L);
        assertThat(results).extracting(
                        InventoryAcquisitionResult::inventoryBucketId,
                        InventoryAcquisitionResult::menuId,
                        InventoryAcquisitionResult::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3L, 3L, 2),
                        org.assertj.core.groups.Tuple.tuple(9L, 9L, 2));
        assertThat(results.getFirst().onlineHoldQuantity()).isEqualTo(2);
        assertThat(results.getLast().onlineHoldQuantity()).isEqualTo(1);
        assertThat(results.getLast().sharedQuantity()).isEqualTo(1);
        InOrder order = org.mockito.Mockito.inOrder(bucketRepository, ledgerRepository);
        order.verify(bucketRepository)
                .findRequestedAndCurrentForUpdate(List.of(3L, 9L));
        order.verify(ledgerRepository).saveAll(org.mockito.ArgumentMatchers.anyList());
        then(bucketRepository).should()
                .findRequestedAndCurrentForUpdate(List.of(3L, 9L));
    }

    @Test
    void restoresRecordedPoolsFromSourceOperation() {
        MenuInventoryBucket bucket = bucket(3L, 2, 2);
        bucket.acquire(3);
        InventoryRestoreRequest request = new InventoryRestoreRequest(
                "reservation:77:cancel", "reservation:77:create");
        given(ledgerRepository.findAcquireResults(request.sourceAcquireOperationId()))
                .willReturn(List.of(new InventoryAllocationResult(3L, 2, 1)));
        given(ledgerRepository.existsRestoreForSourceOperation(
                request.sourceAcquireOperationId()))
                .willReturn(false);
        given(bucketRepository.findRequestedAndCurrentForUpdate(List.of(3L)))
                .willReturn(List.of(bucket));
        given(bucketRepository.incrementIfCurrent(3L, 0L, 2, 1)).willReturn(1);
        MenuInventoryService service = service();

        service.restoreInventory(request);

        then(bucketRepository).should().incrementIfCurrent(3L, 0L, 2, 1);
        then(ledgerRepository).should().saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void restoresOriginalAndCurrentBucketsUnderOneOrderedLock() {
        MenuInventoryBucket original = bucket(3L, 1L, 2, 0);
        MenuInventoryBucket current = bucket(3L, 2L, 2, 0);
        ReflectionTestUtils.setField(current, "id", 9L);
        original.acquire(2);
        current.acquire(2);
        InventoryRestoreRequest request = new InventoryRestoreRequest(
                "reservation:77:cancel", "reservation:77:create");
        given(ledgerRepository.findAcquireResults(request.sourceAcquireOperationId()))
                .willReturn(List.of(new InventoryAllocationResult(3L, 2, 0)));
        given(ledgerRepository.existsRestoreForSourceOperation(
                request.sourceAcquireOperationId()))
                .willReturn(false);
        given(bucketRepository.findRequestedAndCurrentForUpdate(List.of(3L)))
                .willReturn(List.of(original, current));
        given(bucketRepository.incrementIfCurrent(3L, 0L, 2, 0)).willReturn(1);
        given(bucketRepository.incrementIfCurrent(9L, 0L, 2, 0)).willReturn(1);
        MenuInventoryService service = service();

        service.restoreInventory(request);

        then(bucketRepository).should()
                .findRequestedAndCurrentForUpdate(List.of(3L));
        then(bucketRepository).should(org.mockito.Mockito.never())
                .findCurrentForUpdate(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        then(bucketRepository).should().incrementIfCurrent(3L, 0L, 2, 0);
        then(bucketRepository).should().incrementIfCurrent(9L, 0L, 2, 0);
    }

    @Test
    void rejectsAcquireWhenConditionalUpdateLosesCurrentVersion() {
        MenuInventoryBucket bucket = bucket(3L, 2, 0);
        InventoryAcquireRequest request = new InventoryAcquireRequest(
                "reservation:77:create", List.of(selection(3L, 2)));
        given(bucketRepository.findBucketId(selection(3L, 2).key())).willReturn(3L);
        given(bucketRepository.findRequestedAndCurrentForUpdate(List.of(3L)))
                .willReturn(List.of(bucket));
        given(bucketRepository.decrementIfCurrent(3L, 0L, 2, 0)).willReturn(0);
        MenuInventoryService service = service();

        assertThatThrownBy(() -> service.acquireInventory(request))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        then(ledgerRepository).should(org.mockito.Mockito.never())
                .saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void rejectsRestoreWhenSourceAcquireLedgerDoesNotExist() {
        InventoryRestoreRequest request = new InventoryRestoreRequest(
                "reservation:77:cancel", "reservation:missing:create");
        given(ledgerRepository.findAcquireResults(request.sourceAcquireOperationId()))
                .willReturn(List.of());
        MenuInventoryService service = service();

        assertThatThrownBy(() -> service.restoreInventory(request))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.BUCKET_NOT_FOUND);
    }

    @Test
    void rechecksSourceOperationAfterLockToPreventDuplicateRestore() {
        MenuInventoryBucket bucket = bucket(3L, 2, 1);
        bucket.acquire(2);
        InventoryRestoreRequest request = new InventoryRestoreRequest(
                "reservation:77:cancel", "reservation:77:create");
        given(ledgerRepository.findAcquireResults(request.sourceAcquireOperationId()))
                .willReturn(List.of(new InventoryAllocationResult(3L, 2, 0)));
        given(ledgerRepository.existsRestoreForSourceOperation(
                request.sourceAcquireOperationId()))
                .willReturn(false);
        given(ledgerRepository.existsRestoreForSourceOperationForUpdate(
                request.sourceAcquireOperationId()))
                .willReturn(true);
        given(bucketRepository.findRequestedAndCurrentForUpdate(List.of(3L)))
                .willReturn(List.of(bucket));
        MenuInventoryService service = service();

        service.restoreInventory(request);

        assertThat(bucket.getOnlineHoldRemaining()).isZero();
        then(ledgerRepository).should(org.mockito.Mockito.never())
                .saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    private MenuInventoryService service() {
        return new MenuInventoryService(bucketRepository, ledgerRepository);
    }

    private static InventoryAcquireRequest.Selection selection(long menuId, int quantity) {
        return new InventoryAcquireRequest.Selection(
                menuId,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(12, 0),
                LocalDate.of(2026, 8, 10),
                LocalTime.of(13, 0),
                1L,
                quantity);
    }

    private static MenuInventoryBucket bucket(long id, int online, int shared) {
        return bucket(id, 1L, online, shared);
    }

    private static MenuInventoryBucket bucket(
            long id,
            long policyVersion,
            int online,
            int shared
    ) {
        MenuInventoryBucket bucket = MenuInventoryBucket.create(
                id,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(12, 0),
                LocalDate.of(2026, 8, 10),
                LocalTime.of(13, 0),
                "Asia/Seoul",
                policyVersion,
                online + shared,
                online,
                0,
                shared,
                true);
        ReflectionTestUtils.setField(bucket, "id", id);
        return bucket;
    }
}
