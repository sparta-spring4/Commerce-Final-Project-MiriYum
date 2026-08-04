package com.miriyum.domain.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.model.StoreGeocodingCandidate;
import com.miriyum.domain.store.core.model.StoreGeocodingResult;
import com.miriyum.domain.store.core.model.VerifiedStoreGeocoding;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StoreGeocodingValidatorTest {

    private static final Instant VERIFIED_AT = Instant.parse("2026-08-04T09:00:00Z");
    private static final String REQUESTED_ADDRESS = "서울 중구 세종대로 110";

    private final StoreGeocodingValidator validator = new StoreGeocodingValidator();

    @ParameterizedTest
    @MethodSource("invalidCardinalityResults")
    @DisplayName("전체 후보 수와 반환 후보가 모두 정확히 한 건이 아니면 거부한다")
    void rejectsNonUniqueCandidateCardinality(StoreGeocodingResult result) {
        assertValidationFailure(() -> validator.validate(
                Region.SEOUL,
                REQUESTED_ADDRESS,
                result,
                VERIFIED_AT));
    }

    @ParameterizedTest
    @MethodSource("invalidCoordinateCandidates")
    @DisplayName("숫자가 아니거나 허용 범위를 벗어난 좌표는 거부한다")
    void rejectsInvalidCoordinates(StoreGeocodingCandidate candidate) {
        assertValidationFailure(() -> validator.validate(
                Region.SEOUL,
                REQUESTED_ADDRESS,
                result(candidate),
                VERIFIED_AT));
    }

    @ParameterizedTest
    @MethodSource("missingProviderMetadata")
    @DisplayName("provider 또는 API version 메타데이터가 없으면 거부한다")
    void rejectsMissingProviderMetadata(String provider, String providerApiVersion) {
        StoreGeocodingResult result = new StoreGeocodingResult(
                1,
                List.of(validCandidate()),
                provider,
                providerApiVersion);

        assertValidationFailure(() -> validator.validate(
                Region.SEOUL,
                REQUESTED_ADDRESS,
                result,
                VERIFIED_AT));
    }

    @Test
    @DisplayName("단일 후보의 유효 좌표와 최소 메타데이터를 검증 좌표로 변환한다")
    void returnsVerifiedGeocodingForValidSingleCandidate() {
        VerifiedStoreGeocoding verified = validator.validate(
                Region.SEOUL,
                REQUESTED_ADDRESS,
                result(validCandidate()),
                VERIFIED_AT);

        assertThat(verified.latitude()).isEqualByComparingTo("37.566826000000000");
        assertThat(verified.longitude()).isEqualByComparingTo("126.978656700000000");
        assertThat(verified.verifiedAddress()).isEqualTo(REQUESTED_ADDRESS);
        assertThat(verified.verifiedAt()).isEqualTo(VERIFIED_AT);
        assertThat(verified.provider()).isEqualTo("KAKAO_LOCAL");
        assertThat(verified.providerApiVersion()).isEqualTo("v2");
    }

    @Test
    @DisplayName("공백·구두점·Unicode 표기가 달라도 정식 도로명 뒤의 상세주소는 허용한다")
    void acceptsNormalizedRoadAddressWithSeparatedDetailSuffix() {
        StoreGeocodingCandidate candidate = new StoreGeocodingCandidate(
                "서울특별시 중구 세종대로 110",
                "서울특별시 중구 태평로1가 31",
                "서울특별시",
                "126.978656700000000",
                "37.566826000000000");

        VerifiedStoreGeocoding verified = validator.validate(
                Region.SEOUL,
                "  서울특별시  중구 세종대로 １１０, (3층) ",
                result(candidate),
                VERIFIED_AT);

        assertThat(verified.verifiedAddress()).isEqualTo("서울특별시 중구 세종대로 110");
    }

    @Test
    @DisplayName("도로명 주소가 달라도 지번 주소가 일치하면 해당 정식 주소를 사용한다")
    void acceptsMatchingParcelAddress() {
        StoreGeocodingCandidate candidate = new StoreGeocodingCandidate(
                "서울 중구 세종대로 110",
                "서울 중구 태평로1가 31",
                "서울",
                "126.978656700000000",
                "37.566826000000000");

        VerifiedStoreGeocoding verified = validator.validate(
                Region.SEOUL,
                "서울 중구 태평로1가 31 2층",
                result(candidate),
                VERIFIED_AT);

        assertThat(verified.verifiedAddress()).isEqualTo("서울 중구 태평로1가 31");
    }

    @Test
    @DisplayName("정식 번지의 숫자 접두부만 같은 다른 번지는 거부한다")
    void rejectsDifferentAddressNumberSharingPrefix() {
        StoreGeocodingCandidate candidate = new StoreGeocodingCandidate(
                "서울 중구 세종대로 110",
                null,
                "서울",
                "126.978656700000000",
                "37.566826000000000");

        assertValidationFailure(() -> validator.validate(
                Region.SEOUL,
                "서울 중구 세종대로 1100",
                result(candidate),
                VERIFIED_AT));
    }

    @Test
    @DisplayName("요청 Region과 제공자 최상위 지역이 다르면 거부한다")
    void rejectsRegionMismatch() {
        StoreGeocodingCandidate candidate = new StoreGeocodingCandidate(
                "서울 중구 세종대로 110",
                null,
                "부산광역시",
                "126.978656700000000",
                "37.566826000000000");

        assertValidationFailure(() -> validator.validate(
                Region.SEOUL,
                REQUESTED_ADDRESS,
                result(candidate),
                VERIFIED_AT));
    }

    @Test
    @DisplayName("도로명과 지번 정식 주소가 모두 없으면 거부한다")
    void rejectsCandidateWithoutCanonicalAddress() {
        StoreGeocodingCandidate candidate = new StoreGeocodingCandidate(
                null,
                " ",
                "서울",
                "126.978656700000000",
                "37.566826000000000");

        assertValidationFailure(() -> validator.validate(
                Region.SEOUL,
                REQUESTED_ADDRESS,
                result(candidate),
                VERIFIED_AT));
    }

    @Test
    @DisplayName("정규화 후 주소 토큰이 남지 않는 요청과 후보는 거부한다")
    void rejectsAddressesContainingOnlySeparators() {
        StoreGeocodingCandidate candidate = new StoreGeocodingCandidate(
                "...",
                null,
                "서울",
                "126.978656700000000",
                "37.566826000000000");

        assertValidationFailure(() -> validator.validate(
                Region.SEOUL,
                "---",
                result(candidate),
                VERIFIED_AT));
    }

    @ParameterizedTest
    @MethodSource("missingRequiredStructures")
    @DisplayName("결과·후보·Region·검증 시각의 필수 구조가 없으면 공통 검증 오류로 거부한다")
    void rejectsMissingRequiredStructure(
            Region region,
            StoreGeocodingResult result,
            Instant verifiedAt
    ) {
        assertValidationFailure(() -> validator.validate(
                region,
                REQUESTED_ADDRESS,
                result,
                verifiedAt));
    }

    @ParameterizedTest
    @MethodSource("supportedRegions")
    @DisplayName("지원 Region은 Kakao 최상위 지역의 정식·축약 명칭과 일치한다")
    void acceptsSupportedRegionNames(
            Region region,
            String requestedAddress,
            String region1DepthName
    ) {
        StoreGeocodingCandidate candidate = new StoreGeocodingCandidate(
                requestedAddress,
                null,
                region1DepthName,
                "126.978656700000000",
                "37.566826000000000");

        VerifiedStoreGeocoding verified = validator.validate(
                region,
                requestedAddress,
                result(candidate),
                VERIFIED_AT);

        assertThat(verified.verifiedAddress()).isEqualTo(requestedAddress);
    }

    private static Stream<Arguments> invalidCardinalityResults() {
        StoreGeocodingCandidate candidate = validCandidate();
        return Stream.of(
                Arguments.of(new StoreGeocodingResult(
                        0, List.of(), "KAKAO_LOCAL", "v2")),
                Arguments.of(new StoreGeocodingResult(
                        2, List.of(candidate, candidate), "KAKAO_LOCAL", "v2")),
                Arguments.of(new StoreGeocodingResult(
                        1, List.of(), "KAKAO_LOCAL", "v2")),
                Arguments.of(new StoreGeocodingResult(
                        1, List.of(candidate, candidate), "KAKAO_LOCAL", "v2")));
    }

    private static Stream<Arguments> invalidCoordinateCandidates() {
        return Stream.of(
                Arguments.of(candidate("not-a-number", "126.978656700000000")),
                Arguments.of(candidate("37.566826000000000", "not-a-number")),
                Arguments.of(candidate("90.000000000000001", "126.978656700000000")),
                Arguments.of(candidate("-90.000000000000001", "126.978656700000000")),
                Arguments.of(candidate("37.566826000000000", "180.000000000000001")),
                Arguments.of(candidate("37.566826000000000", "-180.000000000000001")),
                Arguments.of(candidate(" ", "126.978656700000000")),
                Arguments.of(candidate("37.566826000000000", null)));
    }

    private static Stream<Arguments> missingProviderMetadata() {
        return Stream.of(
                Arguments.of(null, "v2"),
                Arguments.of(" ", "v2"),
                Arguments.of("KAKAO_LOCAL", null),
                Arguments.of("KAKAO_LOCAL", " "));
    }

    private static Stream<Arguments> missingRequiredStructures() {
        return Stream.of(
                Arguments.of(Region.SEOUL, null, VERIFIED_AT),
                Arguments.of(
                        Region.SEOUL,
                        new StoreGeocodingResult(1, null, "KAKAO_LOCAL", "v2"),
                        VERIFIED_AT),
                Arguments.of(
                        Region.SEOUL,
                        new StoreGeocodingResult(
                                1,
                                Collections.singletonList(null),
                                "KAKAO_LOCAL",
                                "v2"),
                        VERIFIED_AT),
                Arguments.of(Region.SEOUL, result(validCandidate()), null),
                Arguments.of(null, result(validCandidate()), VERIFIED_AT));
    }

    private static Stream<Arguments> supportedRegions() {
        return Stream.of(
                Arguments.of(Region.SEOUL, "서울 중구 세종대로 110", "서울특별시"),
                Arguments.of(Region.BUSAN, "부산 연제구 중앙대로 1001", "부산광역시"),
                Arguments.of(Region.DAEGU, "대구 중구 공평로 88", "대구"),
                Arguments.of(Region.DAEJEON, "대전 서구 둔산로 100", "대전광역시"),
                Arguments.of(Region.GWANGJU, "광주 서구 내방로 111", "광주"));
    }

    private static StoreGeocodingResult result(StoreGeocodingCandidate candidate) {
        return new StoreGeocodingResult(
                1,
                List.of(candidate),
                "KAKAO_LOCAL",
                "v2");
    }

    private static StoreGeocodingCandidate validCandidate() {
        return candidate("37.566826000000000", "126.978656700000000");
    }

    private static StoreGeocodingCandidate candidate(String latitude, String longitude) {
        return new StoreGeocodingCandidate(
                REQUESTED_ADDRESS,
                "서울 중구 태평로1가 31",
                "서울",
                longitude,
                latitude);
    }

    private void assertValidationFailure(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
