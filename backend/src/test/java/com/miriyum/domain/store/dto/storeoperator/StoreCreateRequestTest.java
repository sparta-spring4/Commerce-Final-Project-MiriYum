package com.miriyum.domain.store.dto.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreCreateRequestTest {

    @Test
    void omittedBusinessTypeUsesRollbackSafeCompatibilityValue() {
        StoreCreateRequest request = requestWithoutBusinessType();

        assertThat(request.businessType()).isNull();
        assertThat(request.compatibilityBusinessType()).isEqualTo(BusinessType.OTHER);
    }

    @Test
    void legacyBusinessTypeIsAcceptedAndPreserved() {
        StoreCreateRequest request = new StoreCreateRequest(
                "1234567890", BusinessType.CAFE, "미리윰", "", Region.SEOUL,
                "서울 중구 세종대로 110", "Asia/Seoul", "CAFE_BAKERY", List.of("DATE"),
                new StoreModesRequest(true, true, true), "미리윰 주식회사", "김대표",
                LocalDate.of(2020, 1, 1), "음식점업", "카페", true, true);

        assertThat(request.compatibilityBusinessType()).isEqualTo(BusinessType.CAFE);
    }

    private static StoreCreateRequest requestWithoutBusinessType() {
        return new StoreCreateRequest(
                "1234567890", "미리윰", "", Region.SEOUL,
                "서울 중구 세종대로 110", "Asia/Seoul", "CAFE_BAKERY", List.of("DATE"),
                new StoreModesRequest(true, true, true), "미리윰 주식회사", "김대표",
                LocalDate.of(2020, 1, 1), "음식점업", "카페", true, true);
    }
}
