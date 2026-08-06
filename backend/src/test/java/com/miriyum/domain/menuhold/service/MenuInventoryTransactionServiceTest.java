package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireResult;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireSelection;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityDateQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreCommand;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAcquireRequest;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAcquisitionResult;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.inventory.dto.OnlineInventoryAvailabilityView;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MenuInventoryTransactionServiceTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);
    private static final LocalTime START_TIME = LocalTime.of(12, 0);
    private static final LocalTime END_TIME = LocalTime.of(13, 0);

    private MenuInventoryBucketRepository bucketRepository;
    private MenuInventoryService inventoryService;
    private MenuInventoryTransactionServiceRuntime service;

    @BeforeEach
    void setUp() {
        bucketRepository = mock(MenuInventoryBucketRepository.class);
        inventoryService = mock(MenuInventoryService.class);
        service = new MenuInventoryTransactionServiceRuntime(bucketRepository, inventoryService);
    }

    @Test
    @DisplayName("온라인 가용량은 ONLINE_HOLD와 허용된 SHARED만 합산하고 메뉴 ID로 정렬한다")
    void findsOnlineAvailabilityWithoutOnsiteQuantity() {
        MenuInventoryAvailabilityQuery query = query(3L, 1L, 2L);
        OnlineInventoryAvailabilityView third =
                view(3L, 4, 5, false, InventoryAvailabilityStatus.AVAILABLE);
        OnlineInventoryAvailabilityView first =
                view(1L, 2, 3, true, InventoryAvailabilityStatus.AVAILABLE);
        OnlineInventoryAvailabilityView second =
                view(2L, 7, 11, true, InventoryAvailabilityStatus.SOLD_OUT);
        given(bucketRepository.findCurrentOnlineAvailability(
                query.menuIds(), SERVICE_DATE, START_TIME, SERVICE_DATE, END_TIME))
                .willReturn(List.of(third, first, second));

        List<MenuInventoryAvailability> result = service.findOnlineAvailability(query);

        assertThat(result).extracting(
                        MenuInventoryAvailability::menuId,
                        MenuInventoryAvailability::availableOnlineQuantity,
                        MenuInventoryAvailability::availabilityStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1L, 5, MenuInventoryAvailability.AvailabilityStatus.AVAILABLE),
                        org.assertj.core.groups.Tuple.tuple(
                                2L, 18, MenuInventoryAvailability.AvailabilityStatus.SOLD_OUT),
                        org.assertj.core.groups.Tuple.tuple(
                                3L, 4, MenuInventoryAvailability.AvailabilityStatus.AVAILABLE));
    }

    @Test
    @DisplayName("요청한 메뉴의 현재 버킷이 하나라도 없으면 일부 결과를 반환하지 않는다")
    void rejectsPartialAvailabilityResult() {
        MenuInventoryAvailabilityQuery query = query(1L, 2L);
        OnlineInventoryAvailabilityView first =
                view(1L, 2, 3, true, InventoryAvailabilityStatus.AVAILABLE);
        given(bucketRepository.findCurrentOnlineAvailability(
                query.menuIds(), SERVICE_DATE, START_TIME, SERVICE_DATE, END_TIME))
                .willReturn(List.of(first));

        assertThatThrownBy(() -> service.findOnlineAvailability(query))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.BUCKET_NOT_FOUND);
    }

    @Test
    @DisplayName("날짜별 온라인 가용량은 존재하는 현재 버킷만 안정적인 조회 순서로 반환한다")
    void findsExistingOnlineAvailabilityByDate() {
        MenuInventoryAvailabilityDateQuery query =
                new MenuInventoryAvailabilityDateQuery(
                        List.of(2L, 1L), SERVICE_DATE);
        OnlineInventoryAvailabilityView first =
                view(1L, 2, 3, true, InventoryAvailabilityStatus.AVAILABLE);
        given(bucketRepository.findCurrentOnlineAvailabilityByDate(
                query.menuIds(), SERVICE_DATE))
                .willReturn(List.of(first));

        List<MenuInventoryAvailability> result =
                service.findOnlineAvailabilityByDate(query);

        assertThat(result).extracting(
                        MenuInventoryAvailability::menuId,
                        MenuInventoryAvailability::availableOnlineQuantity,
                        MenuInventoryAvailability::availabilityStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        1L, 5, MenuInventoryAvailability.AvailabilityStatus.AVAILABLE));
    }

    @Test
    @DisplayName("확보는 공개 선택을 내부 요청으로 변환하고 실제 차감한 버킷을 반환한다")
    void acquiresThroughInternalInventoryRuntime() {
        MenuInventoryAcquireCommand command = new MenuInventoryAcquireCommand(
                "pickup-acquire-01",
                List.of(new MenuInventoryAcquireSelection(
                        1L, SERVICE_DATE, START_TIME, SERVICE_DATE, END_TIME, 2L, 3)));
        given(inventoryService.acquireInventory(org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(new InventoryAcquisitionResult(
                        41L, 1L, 2L, 3, 3, 0)));

        MenuInventoryAcquireResult result = service.acquire(command);

        ArgumentCaptor<InventoryAcquireRequest> captor =
                ArgumentCaptor.forClass(InventoryAcquireRequest.class);
        verify(inventoryService).acquireInventory(captor.capture());
        assertThat(captor.getValue().operationId()).isEqualTo("pickup-acquire-01");
        assertThat(captor.getValue().selections()).containsExactly(
                new InventoryAcquireRequest.Selection(
                        1L, SERVICE_DATE, START_TIME, SERVICE_DATE, END_TIME, 2L, 3));
        assertThat(result.items()).extracting(
                        "inventoryBucketId", "menuId", "inventoryPolicyVersion", "quantity")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(41L, 1L, 2L, 3));
    }

    @Test
    @DisplayName("복구는 새 operation과 최초 확보 operation만 내부 런타임에 전달한다")
    void restoresThroughInternalInventoryRuntime() {
        MenuInventoryRestoreCommand command = new MenuInventoryRestoreCommand(
                "pickup-restore-01", "pickup-acquire-01");

        service.restore(command);

        verify(inventoryService).restoreInventory(new InventoryRestoreRequest(
                "pickup-restore-01", "pickup-acquire-01"));
    }

    private static MenuInventoryAvailabilityQuery query(Long... menuIds) {
        return new MenuInventoryAvailabilityQuery(
                List.of(menuIds), SERVICE_DATE, START_TIME, SERVICE_DATE, END_TIME);
    }

    private static OnlineInventoryAvailabilityView view(
            long menuId,
            int onlineHoldRemaining,
            int sharedRemaining,
            boolean sharedOnlineAllowed,
            InventoryAvailabilityStatus status
    ) {
        OnlineInventoryAvailabilityView view = mock(OnlineInventoryAvailabilityView.class);
        given(view.getMenuId()).willReturn(menuId);
        given(view.getInventoryPolicyVersion()).willReturn(2L);
        given(view.getTimeZoneId()).willReturn("Asia/Seoul");
        given(view.getServiceDate()).willReturn(SERVICE_DATE);
        given(view.getStartTime()).willReturn(START_TIME);
        given(view.getEndDate()).willReturn(SERVICE_DATE);
        given(view.getEndTime()).willReturn(END_TIME);
        given(view.getOnlineHoldRemaining()).willReturn(onlineHoldRemaining);
        given(view.getSharedRemaining()).willReturn(sharedRemaining);
        given(view.isSharedOnlineAllowed()).willReturn(sharedOnlineAllowed);
        given(view.getAvailabilityStatus()).willReturn(status);
        return view;
    }
}
