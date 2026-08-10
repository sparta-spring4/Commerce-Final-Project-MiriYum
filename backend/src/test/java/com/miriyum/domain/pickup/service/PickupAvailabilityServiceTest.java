package com.miriyum.domain.pickup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability.AvailabilityStatus;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityDateQuery;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.domain.pickup.dto.response.PickupAvailability;
import com.miriyum.domain.pickup.dto.response.PickupAvailabilityStatus;
import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.search.dto.PublicMenu;
import com.miriyum.domain.store.search.dto.PublicStoreDetail;
import com.miriyum.domain.store.search.dto.PublicStoreModes;
import com.miriyum.domain.store.search.dto.ReservationAvailability;
import com.miriyum.domain.store.search.service.StorePublicQueryService;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PickupAvailabilityServiceTest {

    private static final long STORE_ID = 22L;
    private static final LocalDate PICKUP_DATE = LocalDate.of(2026, 8, 10);

    @Mock
    private StorePublicQueryService storePublicQueryService;

    @Mock
    private MenuInventoryTransactionService menuInventoryTransactionService;

    private PickupAvailabilityService pickupAvailabilityService;

    @BeforeEach
    void setUp() {
        pickupAvailabilityService = new PickupAvailabilityService(
                storePublicQueryService,
                menuInventoryTransactionService,
                policyAt("2026-08-09T01:00:00Z")
        );
    }

    @Test
    void excludesIntervalExactlyAtItsEndEvenWhenQuantityRemains() {
        pickupAvailabilityService = new PickupAvailabilityService(
                storePublicQueryService,
                menuInventoryTransactionService,
                policyAt("2026-08-10T04:00:00Z")
        );
        when(storePublicQueryService.getDetail(STORE_ID, null, false))
                .thenReturn(storeDetail(OperationStatus.OPEN, true));
        when(storePublicQueryService.getMenus(STORE_ID)).thenReturn(List.of(
                menu(101L, "바질 파스타", true, MenuSellingStatus.SELLING)
        ));
        when(menuInventoryTransactionService.findOnlineAvailabilityByDate(any()))
                .thenReturn(List.of(availability(
                        101L, "Asia/Seoul", LocalTime.NOON,
                        LocalTime.of(13, 0), 3, AvailabilityStatus.AVAILABLE)));

        PickupAvailability result = pickupAvailabilityService.getAvailability(
                STORE_ID, PICKUP_DATE);

        assertThat(result.slots()).isEmpty();
    }

    @Test
    void excludesPastDateIntervalEvenWhenQuantityRemains() {
        LocalDate pastDate = PICKUP_DATE.minusDays(1);
        pickupAvailabilityService = new PickupAvailabilityService(
                storePublicQueryService,
                menuInventoryTransactionService,
                policyAt("2026-08-10T04:00:00Z")
        );
        when(storePublicQueryService.getDetail(STORE_ID, null, false))
                .thenReturn(storeDetail(OperationStatus.OPEN, true));
        when(storePublicQueryService.getMenus(STORE_ID)).thenReturn(List.of(
                menu(101L, "바질 파스타", true, MenuSellingStatus.SELLING)
        ));
        when(menuInventoryTransactionService.findOnlineAvailabilityByDate(any()))
                .thenReturn(List.of(new MenuInventoryAvailability(
                        101L, 3L, "Asia/Seoul", pastDate, LocalTime.NOON,
                        pastDate, LocalTime.of(13, 0), 3,
                        AvailabilityStatus.AVAILABLE)));

        PickupAvailability result = pickupAvailabilityService.getAvailability(
                STORE_ID, pastDate);

        assertThat(result.slots()).isEmpty();
    }

    @Test
    @DisplayName("DST gap에 해당하는 시작 시각은 픽업 가능 조회에서 제외한다")
    void excludesStartTimeInDstGap() {
        LocalDate springForwardDate = LocalDate.of(2026, 3, 8);
        pickupAvailabilityService = new PickupAvailabilityService(
                storePublicQueryService,
                menuInventoryTransactionService,
                policyAt("2026-03-08T05:00:00Z")
        );
        when(storePublicQueryService.getDetail(STORE_ID, null, false))
                .thenReturn(storeDetail(
                        OperationStatus.OPEN, true, "America/New_York"));
        when(storePublicQueryService.getMenus(STORE_ID)).thenReturn(List.of(
                menu(101L, "바질 파스타", true, MenuSellingStatus.SELLING)
        ));
        when(menuInventoryTransactionService.findOnlineAvailabilityByDate(any()))
                .thenReturn(List.of(new MenuInventoryAvailability(
                        101L, 3L, "America/New_York",
                        springForwardDate, LocalTime.of(2, 30),
                        springForwardDate, LocalTime.of(3, 30),
                        3, AvailabilityStatus.AVAILABLE)));

        PickupAvailability result = pickupAvailabilityService.getAvailability(
                STORE_ID, springForwardDate);

        assertThat(result.slots()).isEmpty();
    }

    @Test
    @DisplayName("DST overlap에 해당하는 시작 시각은 픽업 가능 조회에서 제외한다")
    void excludesStartTimeInDstOverlap() {
        LocalDate fallBackDate = LocalDate.of(2026, 11, 1);
        pickupAvailabilityService = new PickupAvailabilityService(
                storePublicQueryService,
                menuInventoryTransactionService,
                policyAt("2026-11-01T04:00:00Z")
        );
        when(storePublicQueryService.getDetail(STORE_ID, null, false))
                .thenReturn(storeDetail(
                        OperationStatus.OPEN, true, "America/New_York"));
        when(storePublicQueryService.getMenus(STORE_ID)).thenReturn(List.of(
                menu(101L, "바질 파스타", true, MenuSellingStatus.SELLING)
        ));
        when(menuInventoryTransactionService.findOnlineAvailabilityByDate(any()))
                .thenReturn(List.of(new MenuInventoryAvailability(
                        101L, 3L, "America/New_York",
                        fallBackDate, LocalTime.of(1, 30),
                        fallBackDate, LocalTime.of(2, 30),
                        3, AvailabilityStatus.AVAILABLE)));

        PickupAvailability result = pickupAvailabilityService.getAvailability(
                STORE_ID, fallBackDate);

        assertThat(result.slots()).isEmpty();
    }

    @Test
    @DisplayName("OPEN 픽업 매장의 SELLING 픽업 메뉴만 날짜별 재고 조회에 전달한다")
    void queriesOnlySellingPickupMenus() {
        when(storePublicQueryService.getDetail(STORE_ID, null, false))
                .thenReturn(storeDetail(OperationStatus.OPEN, true));
        when(storePublicQueryService.getMenus(STORE_ID)).thenReturn(List.of(
                menu(101L, "바질 파스타", true, MenuSellingStatus.SELLING),
                menu(102L, "숨김 메뉴", false, MenuSellingStatus.SELLING),
                menu(103L, "일시 중지", true, MenuSellingStatus.PAUSED)
        ));
        when(menuInventoryTransactionService.findOnlineAvailabilityByDate(any()))
                .thenReturn(List.of(availability(
                        101L, "Asia/Seoul", LocalTime.NOON, 3,
                        AvailabilityStatus.AVAILABLE)));

        PickupAvailability result = pickupAvailabilityService.getAvailability(
                STORE_ID, PICKUP_DATE);

        ArgumentCaptor<MenuInventoryAvailabilityDateQuery> queryCaptor =
                ArgumentCaptor.forClass(MenuInventoryAvailabilityDateQuery.class);
        verify(menuInventoryTransactionService)
                .findOnlineAvailabilityByDate(queryCaptor.capture());
        assertThat(queryCaptor.getValue().menuIds()).containsExactly(101L);
        assertThat(queryCaptor.getValue().pickupDate()).isEqualTo(PICKUP_DATE);
        assertThat(result.storeId()).isEqualTo("22");
        assertThat(result.slots()).singleElement().satisfies(slot -> {
            assertThat(slot.pickupTime()).isEqualTo(LocalTime.NOON);
            assertThat(slot.menus()).singleElement().satisfies(menu -> {
                assertThat(menu.menuId()).isEqualTo("101");
                assertThat(menu.menuName()).isEqualTo("바질 파스타");
                assertThat(menu.unitPrice()).isEqualTo(12_000L);
                assertThat(menu.availableQuantity()).isEqualTo(3);
                assertThat(menu.availabilityStatus())
                        .isEqualTo(PickupAvailabilityStatus.AVAILABLE);
            });
        });
    }

    @Test
    @DisplayName("Store 시간대와 다른 재고 구간은 제외하고 시간·메뉴 순서를 안정화한다")
    void excludesMismatchedTimeZoneAndSortsSlots() {
        when(storePublicQueryService.getDetail(STORE_ID, null, false))
                .thenReturn(storeDetail(OperationStatus.OPEN, true));
        when(storePublicQueryService.getMenus(STORE_ID)).thenReturn(List.of(
                menu(102L, "샌드위치", true, MenuSellingStatus.SELLING),
                menu(101L, "바질 파스타", true, MenuSellingStatus.SELLING)
        ));
        when(menuInventoryTransactionService.findOnlineAvailabilityByDate(any()))
                .thenReturn(List.of(
                        availability(102L, "Asia/Seoul", LocalTime.of(13, 0), 0,
                                AvailabilityStatus.SOLD_OUT),
                        availability(102L, "UTC", LocalTime.of(11, 0), 5,
                                AvailabilityStatus.AVAILABLE),
                        availability(102L, "Asia/Seoul", LocalTime.NOON, 2,
                                AvailabilityStatus.AVAILABLE),
                        availability(101L, "Asia/Seoul", LocalTime.NOON, 1,
                                AvailabilityStatus.AVAILABLE)
                ));

        PickupAvailability result = pickupAvailabilityService.getAvailability(
                STORE_ID, PICKUP_DATE);

        assertThat(result.slots()).extracting(slot -> slot.pickupTime())
                .containsExactly(LocalTime.NOON, LocalTime.of(13, 0));
        assertThat(result.slots().getFirst().menus())
                .extracting(menu -> menu.menuId())
                .containsExactly("101", "102");
        assertThat(result.slots())
                .allSatisfy(slot -> assertThat(slot.pickupTime())
                        .isNotEqualTo(LocalTime.of(11, 0)));
    }

    @Test
    void excludesOnlyMenuWithMultipleCurrentIntervalsAtSamePickupTime() {
        when(storePublicQueryService.getDetail(STORE_ID, null, false))
                .thenReturn(storeDetail(OperationStatus.OPEN, true));
        when(storePublicQueryService.getMenus(STORE_ID)).thenReturn(List.of(
                menu(101L, "Ambiguous menu", true, MenuSellingStatus.SELLING),
                menu(102L, "Unique menu", true, MenuSellingStatus.SELLING)
        ));
        when(menuInventoryTransactionService.findOnlineAvailabilityByDate(any()))
                .thenReturn(List.of(
                        availability(101L, "Asia/Seoul", LocalTime.NOON,
                                LocalTime.of(13, 0), 3, AvailabilityStatus.AVAILABLE),
                        availability(101L, "Asia/Seoul", LocalTime.NOON,
                                LocalTime.of(14, 0), 2, AvailabilityStatus.AVAILABLE),
                        availability(102L, "Asia/Seoul", LocalTime.NOON,
                                LocalTime.of(13, 0), 4, AvailabilityStatus.AVAILABLE)
                ));

        PickupAvailability result = pickupAvailabilityService.getAvailability(
                STORE_ID, PICKUP_DATE);

        assertThat(result.slots()).singleElement().satisfies(slot -> {
            assertThat(slot.pickupTime()).isEqualTo(LocalTime.NOON);
            assertThat(slot.menus()).singleElement().satisfies(menu ->
                    assertThat(menu.menuId()).isEqualTo("102"));
        });
    }

    @Test
    void excludesDuplicateIntervalsWhenOnlyOneHasEnded() {
        pickupAvailabilityService = new PickupAvailabilityService(
                storePublicQueryService,
                menuInventoryTransactionService,
                policyAt("2026-08-10T04:30:00Z")
        );
        when(storePublicQueryService.getDetail(STORE_ID, null, false))
                .thenReturn(storeDetail(OperationStatus.OPEN, true));
        when(storePublicQueryService.getMenus(STORE_ID)).thenReturn(List.of(
                menu(101L, "Ambiguous menu", true, MenuSellingStatus.SELLING)
        ));
        when(menuInventoryTransactionService.findOnlineAvailabilityByDate(any()))
                .thenReturn(List.of(
                        availability(101L, "Asia/Seoul", LocalTime.NOON,
                                LocalTime.of(13, 0), 3, AvailabilityStatus.AVAILABLE),
                        availability(101L, "Asia/Seoul", LocalTime.NOON,
                                LocalTime.of(14, 0), 2, AvailabilityStatus.AVAILABLE)
                ));

        PickupAvailability result = pickupAvailabilityService.getAvailability(
                STORE_ID, PICKUP_DATE);

        assertThat(result.slots()).isEmpty();
    }

    @Test
    @DisplayName("OPEN이 아니거나 픽업 기능이 꺼진 매장은 PICKUP_002로 거절한다")
    void rejectsIneligibleStoreBeforeReadingMenusOrInventory() {
        when(storePublicQueryService.getDetail(STORE_ID, null, false))
                .thenReturn(storeDetail(OperationStatus.TEMPORARILY_CLOSED, true));

        ServiceException exception = catchThrowableOfType(
                ServiceException.class,
                () -> pickupAvailabilityService.getAvailability(STORE_ID, PICKUP_DATE)
        );

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.TRANSACTION_NOT_ELIGIBLE);
        verify(storePublicQueryService, never()).getMenus(STORE_ID);
        verify(menuInventoryTransactionService, never()).findOnlineAvailabilityByDate(any());
    }

    @Test
    @DisplayName("조건을 만족하는 공개 메뉴가 없으면 재고 서비스를 호출하지 않고 빈 슬롯을 반환한다")
    void returnsEmptySlotsWithoutInventoryCall() {
        when(storePublicQueryService.getDetail(STORE_ID, null, false))
                .thenReturn(storeDetail(OperationStatus.OPEN, true));
        when(storePublicQueryService.getMenus(STORE_ID)).thenReturn(List.of(
                menu(101L, "판매 중지", true, MenuSellingStatus.PAUSED)
        ));

        PickupAvailability result = pickupAvailabilityService.getAvailability(
                STORE_ID, PICKUP_DATE);

        assertThat(result.slots()).isEmpty();
        verify(menuInventoryTransactionService, never()).findOnlineAvailabilityByDate(any());
    }

    private static PublicStoreDetail storeDetail(
            OperationStatus operationStatus,
            boolean pickupEnabled
    ) {
        return storeDetail(operationStatus, pickupEnabled, "Asia/Seoul");
    }

    private static PublicStoreDetail storeDetail(
            OperationStatus operationStatus,
            boolean pickupEnabled,
            String timeZoneId
    ) {
        return new PublicStoreDetail(
                Long.toString(STORE_ID), "미리냠", "설명", Region.SEOUL, "서울",
                timeZoneId, "ETC", List.of(), operationStatus,
                new PublicStoreModes(true, true, pickupEnabled),
                List.of(), List.of(), List.of(), ReservationAvailability.NOT_REQUESTED
        );
    }

    private static PublicMenu menu(
            long menuId,
            String name,
            boolean pickupEnabled,
            MenuSellingStatus status
    ) {
        return new PublicMenu(
                Long.toString(menuId), name, "설명", 12_000L, false, "NOODLE",
                List.of(), List.of(), true, pickupEnabled, status
        );
    }

    private static MenuInventoryAvailability availability(
            long menuId,
            String timeZoneId,
            LocalTime startTime,
            int quantity,
            AvailabilityStatus status
    ) {
        return availability(
                menuId, timeZoneId, startTime, startTime.plusHours(1), quantity, status);
    }

    private static MenuInventoryAvailability availability(
            long menuId,
            String timeZoneId,
            LocalTime startTime,
            LocalTime endTime,
            int quantity,
            AvailabilityStatus status
    ) {
        return new MenuInventoryAvailability(
                menuId, 3L, timeZoneId, PICKUP_DATE, startTime,
                PICKUP_DATE, endTime, quantity, status
        );
    }

    private static PickupIntervalTimePolicy policyAt(String instant) {
        return new PickupIntervalTimePolicy(Clock.fixed(
                Instant.parse(instant), ZoneOffset.UTC));
    }
}
