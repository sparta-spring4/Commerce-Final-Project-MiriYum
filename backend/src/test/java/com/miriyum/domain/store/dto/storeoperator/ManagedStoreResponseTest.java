package com.miriyum.domain.store.dto.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.GeocodingStatus;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import com.miriyum.domain.store.enums.VerificationStatus;
import java.util.Set;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ManagedStoreResponseTest {

    @Test
    void managedResponseDoesNotExposePickupEligibility() {
        assertThat(Arrays.stream(ManagedStoreResponse.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("pickupEligibility");
    }

    @Test
    @DisplayName("매장 aggregate를 운영자 응답의 세 상태 축과 모드로 변환한다")
    void mapsStoreToManagedResponse() {
        Store store = Store.create(
                11L,
                "1234567890",
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of("DATE"),
                true,
                false,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 7, 31, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1");
        ReflectionTestUtils.setField(store, "id", 9_007_199_254_740_993L);

        ManagedStoreResponse response = ManagedStoreResponse.from(store);

        assertThat(response.storeId()).isEqualTo("9007199254740993");
        assertThat(response.name()).isEqualTo("미리윰");
        assertThat(response.region()).isEqualTo(Region.SEOUL);
        assertThat(response.timeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(response.verificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(response.operationStatus()).isEqualTo(OperationStatus.OPEN);
        assertThat(response.modes())
                .isEqualTo(new StoreModesRequest(true, false, true));
        assertThat(response.geocoding()).isEqualTo(new StoreGeocodingResponse(
                GeocodingStatus.UNVERIFIED,
                null,
                null,
                null,
                null,
                1L));
    }

    @Test
    @DisplayName("검증 좌표는 안전한 운영자 응답 필드만 공개한다")
    void mapsVerifiedGeocodingToSafeManagedResponse() {
        Store store = Store.createVerified(
                11L,
                "1234567890",
                "미리윰",
                "",
                Region.SEOUL,
                "서울 중구 세종대로 110",
                "CAFE_BAKERY",
                Set.of("DATE"),
                true,
                false,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 7, 31, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1",
                new VerifiedStoreGeocoding(
                        new BigDecimal("37.566826000000000"),
                        new BigDecimal("126.978656700000000"),
                        "서울 중구 세종대로 110",
                        Instant.parse("2026-08-04T09:00:00Z")));
        ReflectionTestUtils.setField(store, "id", 7L);

        ManagedStoreResponse response = ManagedStoreResponse.from(store);

        assertThat(response.geocoding()).isEqualTo(new StoreGeocodingResponse(
                GeocodingStatus.VERIFIED,
                new BigDecimal("37.566826000000000"),
                new BigDecimal("126.978656700000000"),
                "서울 중구 세종대로 110",
                Instant.parse("2026-08-04T09:00:00Z"),
                1L));
    }
}
