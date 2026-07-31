package com.miriyum.domain.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.core.dto.StoreCreateRequest;
import com.miriyum.domain.store.core.dto.StoreModesRequest;
import com.miriyum.domain.store.core.dto.StoreUpdateRequest;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoreCommandFingerprintTest {

    @Test
    @DisplayName("태그 입력 순서는 매장 등록 fingerprint를 바꾸지 않는다")
    void tagOrderDoesNotChangeCreateFingerprint() {
        StoreCreateRequest first = requestWithTags(List.of("DATE", "QUIET"));
        StoreCreateRequest second = requestWithTags(List.of("QUIET", "DATE"));

        assertThat(StoreCommandFingerprint.forCreate(first))
                .isEqualTo(StoreCommandFingerprint.forCreate(second));
    }

    @Test
    @DisplayName("대상 매장 ID가 다르면 수정 fingerprint가 달라진다")
    void targetStoreIdChangesUpdateFingerprint() {
        StoreUpdateRequest request = updateName("새 이름");

        assertThat(StoreCommandFingerprint.forUpdate(1L, request))
                .isNotEqualTo(StoreCommandFingerprint.forUpdate(2L, request));
    }

    @Test
    @DisplayName("누락된 설명과 명시적으로 비운 설명은 다른 수정 요청이다")
    void absentAndEmptyDescriptionHaveDifferentFingerprints() {
        StoreUpdateRequest absent = updateName("새 이름");
        StoreUpdateRequest empty = new StoreUpdateRequest(
                "새 이름", "", null, null, null, null, null, null);

        assertThat(StoreCommandFingerprint.forUpdate(1L, absent))
                .isNotEqualTo(StoreCommandFingerprint.forUpdate(1L, empty));
    }

    private StoreCreateRequest requestWithTags(List<String> tags) {
        return new StoreCreateRequest(
                "1234567890",
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                tags,
                new StoreModesRequest(true, true, true));
    }

    private StoreUpdateRequest updateName(String name) {
        return new StoreUpdateRequest(
                name, null, null, null, null, null, null, null);
    }
}
