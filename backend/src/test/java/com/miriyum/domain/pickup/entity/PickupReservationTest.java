package com.miriyum.domain.pickup.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PickupReservationTest {

    private static final Instant PICKUP_AT = Instant.parse("2026-08-10T03:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-08-09T01:00:00Z");
    private static final Instant TERMINATED_AT = Instant.parse("2026-08-09T02:00:00Z");

    @Test
    @DisplayName("거래 시점 스냅샷과 실제 확보 버킷으로 픽업 예약을 즉시 확정한다")
    void confirmsPickupFromAcquiredInventorySnapshot() {
        PickupReservation reservation = confirmedPickup();

        assertThat(reservation.getConsumerAccountId()).isEqualTo(11L);
        assertThat(reservation.getStoreId()).isEqualTo(22L);
        assertThat(reservation.getStoreNameSnapshot()).isEqualTo("미리냠 강남점");
        assertThat(reservation.getTimeZoneIdSnapshot()).isEqualTo("Asia/Seoul");
        assertThat(reservation.getPickupDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(reservation.getPickupTime()).isEqualTo(LocalTime.NOON);
        assertThat(reservation.getPickupAt()).isEqualTo(PICKUP_AT);
        assertThat(reservation.getAcquireOperationId()).isEqualTo("pickup-acquire-1");
        assertThat(reservation.getStatus()).isEqualTo(PickupStatus.CONFIRMED);
        assertThat(reservation.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getPickedUpAt()).isNull();
        assertThat(reservation.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getMenuId()).isEqualTo(33L);
            assertThat(item.getMenuInventoryBucketId()).isEqualTo(44L);
            assertThat(item.getMenuPolicyVersion()).isEqualTo(5L);
            assertThat(item.getInventoryPolicyVersion()).isEqualTo(6L);
            assertThat(item.getMenuNameSnapshot()).isEqualTo("바질 파스타");
            assertThat(item.getUnitPriceSnapshot()).isEqualTo(12_000);
            assertThat(item.getQuantity()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("소비자 취소는 선택 사유와 취소 주체를 기록한다")
    void cancelsByConsumer() {
        PickupReservation reservation = confirmedPickup();

        reservation.cancelByConsumer("일정 변경", TERMINATED_AT);

        assertThat(reservation.getStatus()).isEqualTo(PickupStatus.CANCELLED);
        assertThat(reservation.getCancelledBy()).isEqualTo(PickupCancellationActor.CONSUMER);
        assertThat(reservation.getCancellationReason()).isEqualTo("일정 변경");
        assertThat(reservation.getCancelledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getPickedUpAt()).isNull();
    }

    @Test
    @DisplayName("운영자 취소는 필수 사유와 취소 주체를 기록한다")
    void cancelsByStoreOperator() {
        PickupReservation reservation = confirmedPickup();

        reservation.cancelByStoreOperator("재료 소진", TERMINATED_AT);

        assertThat(reservation.getStatus()).isEqualTo(PickupStatus.CANCELLED);
        assertThat(reservation.getCancelledBy())
                .isEqualTo(PickupCancellationActor.STORE_OPERATOR);
        assertThat(reservation.getCancellationReason()).isEqualTo("재료 소진");
    }

    @Test
    @DisplayName("운영자 취소 사유가 비어 있으면 거부한다")
    void rejectsBlankStoreOperatorCancellationReason() {
        PickupReservation reservation = confirmedPickup();

        assertThatIllegalArgumentException().isThrownBy(() ->
                reservation.cancelByStoreOperator(" ", TERMINATED_AT));
        assertThat(reservation.getStatus()).isEqualTo(PickupStatus.CONFIRMED);
    }

    @Test
    @DisplayName("수령 완료는 PICKED_UP으로 종결한다")
    void marksPickupAsPickedUp() {
        PickupReservation reservation = confirmedPickup();

        reservation.pickUp(TERMINATED_AT);

        assertThat(reservation.getStatus()).isEqualTo(PickupStatus.PICKED_UP);
        assertThat(reservation.getPickedUpAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("종결된 픽업 예약은 다른 상태나 같은 상태로 다시 전이할 수 없다")
    void rejectsEveryTransitionFromTerminalState() {
        PickupReservation cancelled = confirmedPickup();
        cancelled.cancelByConsumer(null, TERMINATED_AT);
        PickupReservation pickedUp = confirmedPickup();
        pickedUp.pickUp(TERMINATED_AT);

        ServiceException cancelAgain = catchThrowableOfType(
                ServiceException.class,
                () -> cancelled.cancelByConsumer(null, TERMINATED_AT.plusSeconds(1))
        );
        ServiceException fulfillCancelled = catchThrowableOfType(
                ServiceException.class,
                () -> cancelled.pickUp(TERMINATED_AT.plusSeconds(1))
        );
        ServiceException cancelPickedUp = catchThrowableOfType(
                ServiceException.class,
                () -> pickedUp.cancelByStoreOperator(
                        "오처리 정정", TERMINATED_AT.plusSeconds(1))
        );
        ServiceException fulfillAgain = catchThrowableOfType(
                ServiceException.class,
                () -> pickedUp.pickUp(TERMINATED_AT.plusSeconds(1))
        );

        assertThat(List.of(cancelAgain, fulfillCancelled, cancelPickedUp, fulfillAgain))
                .allSatisfy(exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(PickupErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("최초 확보 operation ID와 실제 버킷은 필수이며 중복 버킷을 거부한다")
    void rejectsInvalidAcquisitionIdentity() {
        PickupItemSnapshot item = itemSnapshot();

        assertThatIllegalArgumentException().isThrownBy(() -> confirm(" ", List.of(item)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                confirm("a".repeat(101), List.of(item)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                confirm("pickup-acquire-1", List.of(item, item)));
    }

    private static PickupReservation confirmedPickup() {
        return confirm("pickup-acquire-1", List.of(itemSnapshot()));
    }

    private static PickupReservation confirm(
            String acquireOperationId,
            List<PickupItemSnapshot> items
    ) {
        return PickupReservation.confirm(
                11L, 22L, "미리냠 강남점", "Asia/Seoul",
                LocalDate.of(2026, 8, 10), LocalTime.NOON, PICKUP_AT,
                acquireOperationId, items, CREATED_AT
        );
    }

    private static PickupItemSnapshot itemSnapshot() {
        return new PickupItemSnapshot(
                33L, 44L, 5L, "바질 파스타", 12_000, 6L, 2
        );
    }
}
