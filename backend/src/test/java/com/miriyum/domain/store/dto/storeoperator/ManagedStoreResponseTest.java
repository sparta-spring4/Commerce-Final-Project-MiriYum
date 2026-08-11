package com.miriyum.domain.store.dto.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
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
                BusinessType.CAFE,
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
    }
}
