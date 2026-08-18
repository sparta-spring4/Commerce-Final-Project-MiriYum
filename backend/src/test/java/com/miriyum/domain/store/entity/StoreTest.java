package com.miriyum.domain.store.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.GeocodingStatus;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoreTest {

    private static final String TIME_ZONE_ID = "Asia/Seoul";
    private static final LocalDateTime ONBOARDING_ACCEPTED_AT =
            LocalDateTime.of(2026, 7, 31, 12, 0);
    private static final String REQUIRED_TERMS_VERSION =
            "STORE_ONBOARDING_REQUIRED_TERMS_V1";
    private static final Instant GEOCODING_VERIFIED_AT =
            Instant.parse("2026-08-04T09:00:00Z");

    @Test
    @DisplayName("검증 생성은 주소 버전 1과 현재 버전에 결합된 좌표로 시작한다")
    void verifiedCreateBindsCoordinatesToAddressVersionOne() {
        Store store = createVerifiedStore(verifiedGeocoding(
                "37.566826000000000",
                "126.978656700000000",
                "서울 중구 세종대로 110"));

        assertThat(store.getAddressVersion()).isEqualTo(1L);
        assertThat(store.getGeocodingStatus()).isEqualTo(GeocodingStatus.VERIFIED);
        assertThat(store.getLatitude()).isEqualByComparingTo("37.566826000000000");
        assertThat(store.getLongitude()).isEqualByComparingTo("126.978656700000000");
        assertThat(store.getVerifiedAddress()).isEqualTo("서울 중구 세종대로 110");
        assertThat(store.getGeocodingVerifiedAt()).isEqualTo(GEOCODING_VERIFIED_AT);
        assertThat(store.getGeocodingAddressVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("레거시 생성은 주소 버전 1과 좌표 없는 미검증 상태를 유지한다")
    void legacyCreateHasUnverifiedGeocodingShape() {
        Store store = cafeStore("미리윰");

        assertThat(store.getDashboardAuthorityVersion()).isEqualTo(1L);
        assertThat(store.getAddressVersion()).isEqualTo(1L);
        assertThat(store.getGeocodingStatus()).isEqualTo(GeocodingStatus.UNVERIFIED);
        assertThat(store.getLatitude()).isNull();
        assertThat(store.getLongitude()).isNull();
        assertThat(store.getVerifiedAddress()).isNull();
        assertThat(store.getGeocodingVerifiedAt()).isNull();
        assertThat(store.getGeocodingAddressVersion()).isNull();
    }

    @Test
    @DisplayName("위치 변경은 주소 버전을 한 번 올리고 모든 검증 좌표를 교체한다")
    void locationUpdateIncrementsVersionAndReplacesGeocoding() {
        Store store = createVerifiedStore(verifiedGeocoding(
                "37.566826000000000",
                "126.978656700000000",
                "서울 중구 세종대로 110"));
        VerifiedStoreGeocoding changed = new VerifiedStoreGeocoding(
                new BigDecimal("35.179554300000000"),
                new BigDecimal("129.075641600000000"),
                "부산 연제구 중앙대로 1001",
                Instant.parse("2026-08-04T10:00:00Z"));

        store.update(
                null, null, Region.BUSAN, "부산 연제구 중앙대로 1001",
                null, null, null, null, null, null, changed);

        assertThat(store.getRegion()).isEqualTo(Region.BUSAN);
        assertThat(store.getAddress()).isEqualTo("부산 연제구 중앙대로 1001");
        assertThat(store.getAddressVersion()).isEqualTo(2L);
        assertThat(store.getGeocodingAddressVersion()).isEqualTo(2L);
        assertThat(store.getLatitude()).isEqualByComparingTo("35.179554300000000");
        assertThat(store.getLongitude()).isEqualByComparingTo("129.075641600000000");
        assertThat(store.getVerifiedAddress()).isEqualTo("부산 연제구 중앙대로 1001");
    }

    @Test
    @DisplayName("위치가 아닌 수정은 기존 주소 버전과 검증 좌표를 유지한다")
    void nonLocationUpdatePreservesGeocoding() {
        Store store = createVerifiedStore(verifiedGeocoding(
                "37.566826000000000",
                "126.978656700000000",
                "서울 중구 세종대로 110"));

        store.update(
                "새 이름", null, null, null, null, null,
                null, null, null, null, null);

        assertThat(store.getName()).isEqualTo("새 이름");
        assertThat(store.getAddressVersion()).isEqualTo(1L);
        assertThat(store.getGeocodingAddressVersion()).isEqualTo(1L);
        assertThat(store.getLatitude()).isEqualByComparingTo("37.566826000000000");
    }

    @Test
    @DisplayName("검증 좌표 없는 위치 변경은 어떤 위치 필드도 바꾸지 않는다")
    void locationUpdateWithoutVerifiedGeocodingIsRejectedBeforeMutation() {
        Store store = createVerifiedStore(verifiedGeocoding(
                "37.566826000000000",
                "126.978656700000000",
                "서울 중구 세종대로 110"));

        assertThatThrownBy(() -> store.update(
                null, null, Region.BUSAN, "부산 연제구 중앙대로 1001",
                null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.getRegion()).isEqualTo(Region.SEOUL);
        assertThat(store.getAddress()).isEqualTo("서울 중구 세종대로 110");
        assertThat(store.getAddressVersion()).isEqualTo(1L);
        assertThat(store.getGeocodingAddressVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("불완전한 검증 좌표는 위치와 주소 버전을 바꾸기 전에 거부한다")
    void incompleteVerifiedGeocodingIsRejectedBeforeMutation() {
        Store store = createVerifiedStore(verifiedGeocoding(
                "37.566826000000000",
                "126.978656700000000",
                "서울 중구 세종대로 110"));
        VerifiedStoreGeocoding incomplete = new VerifiedStoreGeocoding(
                new BigDecimal("35.179554300000000"),
                new BigDecimal("129.075641600000000"),
                " ",
                Instant.parse("2026-08-04T10:00:00Z"));

        assertThatThrownBy(() -> store.update(
                null, null, Region.BUSAN, "부산 연제구 중앙대로 1001",
                null, null, null, null, null, null, incomplete))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.getRegion()).isEqualTo(Region.SEOUL);
        assertThat(store.getAddress()).isEqualTo("서울 중구 세종대로 110");
        assertThat(store.getAddressVersion()).isEqualTo(1L);
        assertThat(store.getGeocodingAddressVersion()).isEqualTo(1L);
        assertThat(store.getLatitude()).isEqualByComparingTo("37.566826000000000");
    }

    @Test
    @DisplayName("검증 생성은 좌표 범위와 DECIMAL(18,15) 정밀도를 벗어난 값을 거부한다")
    void verifiedCreateRejectsOutOfRangeAndOverPrecisionCoordinates() {
        for (VerifiedStoreGeocoding invalid : invalidGeocodings()) {
            assertThatThrownBy(() -> createVerifiedStore(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("위치 변경은 잘못된 좌표를 거부하고 기존 위치와 좌표를 보존한다")
    void locationUpdateRejectsInvalidCoordinatesBeforeMutation() {
        for (VerifiedStoreGeocoding invalid : invalidGeocodings()) {
            Store store = createVerifiedStore(verifiedGeocoding(
                    "37.566826000000000",
                    "126.978656700000000",
                    "서울 중구 세종대로 110"));

            assertThatThrownBy(() -> store.update(
                    null, null, Region.BUSAN, "부산 연제구 중앙대로 1001",
                    null, null, null, null, null, null, invalid))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(store.getRegion()).isEqualTo(Region.SEOUL);
            assertThat(store.getAddress()).isEqualTo("서울 중구 세종대로 110");
            assertThat(store.getAddressVersion()).isEqualTo(1L);
            assertThat(store.getLatitude()).isEqualByComparingTo("37.566826000000000");
            assertThat(store.getLongitude()).isEqualByComparingTo("126.978656700000000");
        }
    }

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
                true,
                TIME_ZONE_ID,
                ONBOARDING_ACCEPTED_AT,
                REQUIRED_TERMS_VERSION);

        assertThat(store.getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.OPEN);
        assertThat(store.getTimeZoneId()).isEqualTo(TIME_ZONE_ID);
    }

    @Test
    @DisplayName("IANA 데이터베이스에 없는 매장 시간대는 생성할 수 없다")
    void unknownIanaTimeZoneIsRejected() {
        assertThatThrownBy(() -> Store.create(
                11L,
                "1234567890",
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of(),
                true,
                false,
                false,
                "Mars/Olympus",
                ONBOARDING_ACCEPTED_AT,
                REQUIRED_TERMS_VERSION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("고정 UTC 오프셋은 IANA 시간대 식별자가 아니므로 거부한다")
    void fixedOffsetTimeZoneIsRejected() {
        assertThatThrownBy(() -> Store.create(
                11L,
                "1234567890",
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of(),
                true,
                false,
                false,
                "+09:00",
                ONBOARDING_ACCEPTED_AT,
                REQUIRED_TERMS_VERSION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("OTHER 업종도 픽업 기능을 활성화할 수 있다")
    void otherCanEnablePickup() {
        Store store = Store.create(
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
                true,
                TIME_ZONE_ID,
                ONBOARDING_ACCEPTED_AT,
                REQUIRED_TERMS_VERSION);

        assertThat(store.isPickupEnabled()).isTrue();
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
                false,
                TIME_ZONE_ID,
                ONBOARDING_ACCEPTED_AT,
                REQUIRED_TERMS_VERSION);

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
                true,
                TIME_ZONE_ID,
                ONBOARDING_ACCEPTED_AT,
                REQUIRED_TERMS_VERSION);

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
                OperationStatus.TEMPORARILY_CLOSED,
                verifiedGeocoding(
                        "35.871435400000000",
                        "128.601445000000000",
                        "대구 중구 공평로 88"));

        assertThat(store.getName()).isEqualTo("새 이름");
        assertThat(store.getDescription()).isEqualTo("기존 설명");
        assertThat(store.getRegion()).isEqualTo(Region.DAEGU);
        assertThat(store.getAddress()).isEqualTo("기존 주소");
        assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.TEMPORARILY_CLOSED);
        assertThat(store.isPickupEnabled()).isTrue();
    }

    @Test
    @DisplayName("OTHER 매장도 수정으로 픽업 기능을 활성화할 수 있다")
    void otherStoreCanEnablePickupOnUpdate() {
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
                false,
                TIME_ZONE_ID,
                ONBOARDING_ACCEPTED_AT,
                REQUIRED_TERMS_VERSION);

        store.update(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null);

        assertThat(store.isReservationEnabled()).isTrue();
        assertThat(store.isMenuHoldEnabled()).isFalse();
        assertThat(store.isPickupEnabled()).isTrue();
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
                true,
                TIME_ZONE_ID,
                ONBOARDING_ACCEPTED_AT,
                REQUIRED_TERMS_VERSION);
    }

    private Store createVerifiedStore(VerifiedStoreGeocoding geocoding) {
        return Store.createVerified(
                11L,
                "1234567890",
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울 중구 세종대로 110",
                "CAFE_BAKERY",
                Set.of("DATE"),
                true,
                true,
                true,
                TIME_ZONE_ID,
                ONBOARDING_ACCEPTED_AT,
                REQUIRED_TERMS_VERSION,
                geocoding);
    }

    private VerifiedStoreGeocoding verifiedGeocoding(
            String latitude,
            String longitude,
            String verifiedAddress
    ) {
        return new VerifiedStoreGeocoding(
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                verifiedAddress,
                GEOCODING_VERIFIED_AT);
    }

    private List<VerifiedStoreGeocoding> invalidGeocodings() {
        return List.of(
                verifiedGeocoding(
                        "91.000000000000000",
                        "126.978656700000000",
                        "서울 중구 세종대로 110"),
                verifiedGeocoding(
                        "37.566826000000000",
                        "181.000000000000000",
                        "서울 중구 세종대로 110"),
                verifiedGeocoding(
                        "37.1234567890123456",
                        "126.978656700000000",
                        "서울 중구 세종대로 110"));
    }
}
