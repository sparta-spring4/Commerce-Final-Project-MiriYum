package com.miriyum.domain.store.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreModesRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreUpdateRequest;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoreCommandFingerprintTest {

    @Test
    void updateFingerprintUsesCanonicalStoreOperatorNamespace() {
        StoreUpdateRequest request = updateName("MiriYum");

        assertThat(StoreCommandFingerprint.forUpdate(7L, request)).isEqualTo(
                RequestFingerprint.of(
                        "PATCH|/api/v1/store-operators/stores/{storeId}|"
                                + "storeId=1:7|name=7:MiriYum|description=-1:|"
                                + "region=-1:|address=-1:|storeCategoryCode=-1:|"
                                + "tagCodes=-1:|modes=-1:|operationStatus=-1:|"));
    }

    @Test
    @DisplayName("태그 입력 순서는 매장 등록 fingerprint를 바꾸지 않는다")
    void tagOrderDoesNotChangeCreateFingerprint() {
        StoreCreateRequest first = request(
                List.of("DATE", "QUIET"), true, true);
        StoreCreateRequest second = request(
                List.of("QUIET", "DATE"), true, true);

        assertThat(StoreCommandFingerprint.forCreate(first))
                .isEqualTo(StoreCommandFingerprint.forCreate(second));
    }

    @Test
    @DisplayName("자기확약과 필수 약관 동의는 등록 fingerprint에 포함된다")
    void onboardingDeclarationsAffectCreateFingerprint() {
        StoreCreateRequest attested = request(List.of("DATE"), true, true);
        StoreCreateRequest missingAgreement =
                request(List.of("DATE"), true, false);

        assertThat(StoreCommandFingerprint.forCreate(attested))
                .isNotEqualTo(StoreCommandFingerprint.forCreate(missingAgreement));
    }

    @Test
    @DisplayName("매장 시간대가 다르면 등록 fingerprint가 달라진다")
    void timeZoneChangesCreateFingerprint() {
        StoreCreateRequest seoul = request(
                List.of("DATE"), true, true, "Asia/Seoul");
        StoreCreateRequest tokyo = request(
                List.of("DATE"), true, true, "Asia/Tokyo");

        assertThat(StoreCommandFingerprint.forCreate(seoul))
                .isNotEqualTo(StoreCommandFingerprint.forCreate(tokyo));
    }

    @Test
    @DisplayName("생략된 호환 업종과 명시적인 OTHER는 같은 등록 요청이다")
    void omittedAndExplicitOtherBusinessTypeHaveSameFingerprint() {
        StoreCreateRequest omitted = request(List.of("DATE"), true, true);
        StoreCreateRequest explicitOther = new StoreCreateRequest(
                omitted.businessRegistrationNumber(), BusinessType.OTHER,
                omitted.name(), omitted.description(), omitted.region(), omitted.address(),
                omitted.timeZoneId(), omitted.storeCategoryCode(), omitted.tagCodes(),
                omitted.modes(), omitted.legalBusinessName(), omitted.representativeName(),
                omitted.openingDate(), omitted.primaryBusinessCategory(),
                omitted.primaryBusinessItem(), omitted.applicantSelfAttested(),
                omitted.requiredTermsAgreed());

        assertThat(StoreCommandFingerprint.forCreate(omitted))
                .isEqualTo(StoreCommandFingerprint.forCreate(explicitOther));
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

    private StoreCreateRequest request(
            List<String> tags,
            boolean applicantSelfAttested,
            boolean requiredTermsAgreed
    ) {
        return request(
                tags,
                applicantSelfAttested,
                requiredTermsAgreed,
                "Asia/Seoul");
    }

    private StoreCreateRequest request(
            List<String> tags,
            boolean applicantSelfAttested,
            boolean requiredTermsAgreed,
            String timeZoneId
    ) {
        return new StoreCreateRequest(
                "1234567890",
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                timeZoneId,
                "CAFE_BAKERY",
                tags,
                new StoreModesRequest(true, true, true),
                applicantSelfAttested,
                requiredTermsAgreed);
    }

    private StoreUpdateRequest updateName(String name) {
        return new StoreUpdateRequest(
                name, null, null, null, null, null, null, null);
    }
}
