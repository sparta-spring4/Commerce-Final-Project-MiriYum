package com.miriyum.domain.store.core.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.enums.VerificationStatus;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoreTest {

    @Test
    @DisplayName("카페 매장은 승인·영업 중·픽업 가능 상태로 생성된다")
    void cafeIsApprovedOpenAndPickupEligible() {
        Store store = Store.create(
                11L,
                "1234567890",
                BusinessType.CAFE,
                "미리윰",
                "설명",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of("DATE"),
                true,
                true,
                true);

        assertThat(store.getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.OPEN);
        assertThat(store.getPickupEligibility()).isEqualTo(PickupEligibility.ELIGIBLE);
    }

    @Test
    @DisplayName("OTHER 업종은 픽업 기능을 활성화할 수 없다")
    void otherCannotEnablePickup() {
        assertThatThrownBy(() -> Store.create(
                11L,
                "1234567890",
                BusinessType.OTHER,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "ETC",
                Set.of(),
                true,
                false,
                true))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.PICKUP_NOT_ELIGIBLE);
    }

    @Test
    @DisplayName("대표 운영자가 아닌 계정의 관리 요청은 거부한다")
    void otherOperatorIsForbidden() {
        Store store = Store.create(
                11L,
                "1234567890",
                BusinessType.BAKERY,
                "미리윰",
                "",
                Region.BUSAN,
                "부산시 중구",
                "CAFE_BAKERY",
                Set.of(),
                true,
                false,
                false);

        assertThatThrownBy(() -> store.requireManagedBy(12L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("수정 요청에서 전달하지 않은 필드는 유지하고 전달한 필드만 바꾼다")
    void updateChangesOnlyProvidedFields() {
        Store store = Store.create(
                11L,
                "1234567890",
                BusinessType.CAFE,
                "기존 이름",
                "기존 설명",
                Region.SEOUL,
                "기존 주소",
                "CAFE_BAKERY",
                Set.of("DATE"),
                true,
                true,
                true);

        store.update(
                "새 이름",
                null,
                Region.DAEGU,
                null,
                null,
                null,
                null,
                null,
                null,
                OperationStatus.TEMPORARILY_CLOSED);

        assertThat(store.getName()).isEqualTo("새 이름");
        assertThat(store.getDescription()).isEqualTo("기존 설명");
        assertThat(store.getRegion()).isEqualTo(Region.DAEGU);
        assertThat(store.getAddress()).isEqualTo("기존 주소");
        assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.TEMPORARILY_CLOSED);
        assertThat(store.isPickupEnabled()).isTrue();
    }

    @Test
    @DisplayName("OTHER 매장의 수정에서 픽업 활성화를 거부하고 기존 설정을 유지한다")
    void rejectedPickupUpdateKeepsPreviousModes() {
        Store store = Store.create(
                11L,
                "1234567890",
                BusinessType.OTHER,
                "미리윰",
                "",
                Region.GWANGJU,
                "광주시 동구",
                "ETC",
                Set.of(),
                true,
                false,
                false);

        assertThatThrownBy(() -> store.update(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.PICKUP_NOT_ELIGIBLE);

        assertThat(store.isReservationEnabled()).isTrue();
        assertThat(store.isMenuHoldEnabled()).isFalse();
        assertThat(store.isPickupEnabled()).isFalse();
    }

    @Test
    @DisplayName("폐점된 매장은 영업으로 되돌릴 수 없고 다른 수정도 반영하지 않는다")
    void closedStoreCannotReopenAndKeepsOtherFields() {
        Store store = cafeStore("기존 이름");
        store.close();

        assertThatThrownBy(() -> store.update(
                "변경되면 안 됨",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OperationStatus.OPEN))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);

        assertThat(store.getName()).isEqualTo("기존 이름");
        assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.CLOSED);
    }

    @Test
    @DisplayName("폐점된 매장은 휴점으로 되돌릴 수 없다")
    void closedStoreCannotBecomeTemporarilyClosed() {
        Store store = cafeStore("미리윰");
        store.close();

        assertThatThrownBy(() -> store.update(
                null, null, null, null, null, null,
                null, null, null, OperationStatus.TEMPORARILY_CLOSED))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);
    }

    @Test
    @DisplayName("일반 수정에서는 영업과 휴점만 서로 전환할 수 있다")
    void nonTerminalOperationTransitionsRemainAllowed() {
        Store store = cafeStore("미리윰");

        store.update(
                null, null, null, null, null, null,
                null, null, null, OperationStatus.TEMPORARILY_CLOSED);
        assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.TEMPORARILY_CLOSED);

        store.update(
                null, null, null, null, null, null,
                null, null, null, OperationStatus.OPEN);
        assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.OPEN);
    }

    @Test
    @DisplayName("일반 수정으로 폐점을 요청하면 다른 필드도 변경하지 않는다")
    void generalUpdateCannotCloseOrPartiallyMutateStore() {
        Store store = cafeStore("기존 이름");

        assertThatThrownBy(() -> store.update(
                "변경되면 안 됨",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OperationStatus.CLOSED))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);

        assertThat(store.getName()).isEqualTo("기존 이름");
        assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.OPEN);
    }

    private Store cafeStore(String name) {
        return Store.create(
                11L,
                "1234567890",
                BusinessType.CAFE,
                name,
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of("DATE"),
                true,
                true,
                true);
    }
}
