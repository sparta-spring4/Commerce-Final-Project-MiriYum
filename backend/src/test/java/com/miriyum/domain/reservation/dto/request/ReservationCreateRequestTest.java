package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReservationCreateRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("매장 식별자는 문자열 PublicId로 받고 signed long으로 변환한다")
    void acceptsStringStoreIdAndConvertsItToLong() {
        // given
        ReservationCreateRequest request = validRequest("9223372036854775807", List.of());

        // when
        Set<?> violations = VALIDATOR.validate(request);

        // then
        assertThat(violations).isEmpty();
        assertThat(request.storeIdAsLong()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("매장 식별자는 양의 signed long 10진수만 허용한다")
    void validatesStoreIdAsPositiveSignedLongDecimal() {
        // when
        Set<String> zeroPaths = propertyPathsOf(validRequest("0", List.of()));
        Set<String> leadingZeroPaths = propertyPathsOf(validRequest("01", List.of()));
        Set<String> decimalPaths = propertyPathsOf(validRequest("1.0", List.of()));
        Set<String> tooLargePaths = propertyPathsOf(
                validRequest("9223372036854775808", List.of())
        );

        // then
        assertThat(zeroPaths).contains("storeId");
        assertThat(leadingZeroPaths).contains("storeId");
        assertThat(decimalPaths).contains("storeId");
        assertThat(tooLargePaths).contains("storeId");
    }

    @Test
    @DisplayName("매장 식별자 변환은 유효성 검사를 우회하지 않는다")
    void rejectsInvalidStoreIdDuringLongConversion() {
        assertThatThrownBy(() -> validRequest("0", List.of()).storeIdAsLong())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("예약에는 날짜·분 단위 시작 시각과 인원 구성이 모두 필요하다")
    void requiresReservationTimeAndParty() {
        // given
        ReservationCreateRequest request = new ReservationCreateRequest(
                "1", null, null, null, null, List.of()
        );

        // when
        Set<String> paths = propertyPathsOf(request);

        // then
        assertThat(paths).contains("serviceDate", "startTime", "party");
    }

    @Test
    @DisplayName("예약 요청은 중첩된 인원 구성의 합계 검증도 수행한다")
    void validatesNestedPartyComposition() {
        // given
        ReservationCreateRequest request = new ReservationCreateRequest(
                "1",
                LocalDate.of(2026, 8, 7),
                LocalTime.of(18, 0),
                null,
                new ReservationPartyRequest(0, 0, 0),
                List.of()
        );

        // when
        Set<String> paths = propertyPathsOf(request);

        // then
        assertThat(paths).contains("$");
    }

    @Test
    @DisplayName("시작 시각은 초와 나노초 없는 분 단위여야 한다")
    void rejectsStartTimeOutsideMinutePrecision() {
        assertThatThrownBy(() -> new ReservationCreateRequest(
                "1",
                LocalDate.of(2026, 8, 7),
                LocalTime.of(18, 0, 1),
                null,
                new ReservationPartyRequest(1, 0, 0),
                List.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startTime must use minute precision");
    }

    @Test
    @DisplayName("DST offset은 -18:00부터 +18:00까지의 HH:mm 형식만 허용한다")
    void validatesOptionalStartOffsetFormat() {
        // given
        ReservationCreateRequest request = new ReservationCreateRequest(
                "1",
                LocalDate.of(2026, 8, 7),
                LocalTime.of(18, 0),
                "+18:01",
                new ReservationPartyRequest(1, 0, 0),
                List.of()
        );

        // when
        Set<String> paths = propertyPathsOf(request);

        // then
        assertThat(paths).contains("startOffset");
    }

    @Test
    @DisplayName("메뉴 선택을 생략하거나 빈 배열로 보내면 메뉴 없는 예약으로 정규화한다")
    void normalizesMissingMenuSelectionsToEmptyList() {
        // when
        ReservationCreateRequest missing = validRequest("1", null);
        ReservationCreateRequest empty = validRequest("1", List.of());

        // then
        assertThat(missing.menuSelections()).isEmpty();
        assertThat(empty.menuSelections()).isEmpty();
        assertThat(missing.normalizedMenuSelections()).isEmpty();
    }

    @Test
    @DisplayName("매장 식별자는 JSON 문자열 token만 허용한다")
    void rejectsNumericStoreIdJsonToken() {
        String body = """
                {"storeId":1,"serviceDate":null,"startTime":null,
                 "party":{"adultCount":1,"childCount":0,"infantCount":0}}
                """;

        assertThatThrownBy(() -> JSON_MAPPER.readValue(body, ReservationCreateRequest.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("명시적인 menuSelections null은 공개 menuSelections 오류로 거절한다")
    void rejectsExplicitNullMenuSelectionsAtJsonBoundary() throws Exception {
        String body = """
                {"storeId":"1","serviceDate":null,"startTime":null,
                 "party":{"adultCount":1,"childCount":0,"infantCount":0},
                 "menuSelections":null}
                """;

        ReservationCreateRequest request = JSON_MAPPER.readValue(
                body, ReservationCreateRequest.class
        );

        assertThat(propertyPathsOf(request)).contains("menuSelections");
    }

    @Test
    @DisplayName("menuSelections를 생략하거나 빈 배열로 보내면 메뉴 없는 예약으로 읽는다")
    void acceptsOmittedOrEmptyMenuSelectionsAtJsonBoundary() throws Exception {
        String omitted = """
                {"storeId":"1","serviceDate":null,"startTime":null,
                 "party":{"adultCount":1,"childCount":0,"infantCount":0}}
                """;
        String empty = """
                {"storeId":"1","serviceDate":null,"startTime":null,
                 "party":{"adultCount":1,"childCount":0,"infantCount":0},
                 "menuSelections":[]}
                """;

        ReservationCreateRequest omittedRequest = JSON_MAPPER.readValue(
                omitted, ReservationCreateRequest.class
        );
        ReservationCreateRequest emptyRequest = JSON_MAPPER.readValue(
                empty, ReservationCreateRequest.class
        );

        assertThat(omittedRequest.menuSelections()).isEmpty();
        assertThat(emptyRequest.menuSelections()).isEmpty();
    }

    @Test
    @DisplayName("요청 DTO는 공개 JSON 필드만 직렬화한다")
    void serializesOnlyPublicJsonFields() throws Exception {
        String json = JSON_MAPPER.writeValueAsString(validRequest(
                "1", List.of(new ReservationMenuSelectionRequest("2", 1))
        ));

        assertThat(json)
                .contains("\"storeId\":\"1\"", "\"menuSelections\"", "\"menuId\":\"2\"")
                .doesNotContain("menuSelectionsExplicitlyNull", "storeIdAsLong");
    }

    @Test
    @DisplayName("허용하지 않은 client 필드는 JSON 요청에서 거절한다")
    void rejectsUnknownClientFieldAtJsonBoundary() {
        String body = """
                {"storeId":"1","serviceDate":null,"startTime":null,
                 "party":{"adultCount":1,"childCount":0,"infantCount":0},
                 "accountId":"99"}
                """;

        assertThatThrownBy(() -> JSON_MAPPER.readValue(body, ReservationCreateRequest.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("메뉴 선택은 최대 20개이고 각 항목을 검증한다")
    void validatesMenuSelectionCountAndItems() {
        // given
        List<ReservationMenuSelectionRequest> tooMany = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> new ReservationMenuSelectionRequest(
                        String.valueOf(index + 1), 1
                ))
                .toList();
        ReservationCreateRequest request = validRequest(
                "1", List.of(new ReservationMenuSelectionRequest("1", 0))
        );

        // when
        Set<String> tooManyPaths = propertyPathsOf(validRequest("1", tooMany));
        Set<String> itemPaths = propertyPathsOf(request);

        // then
        assertThat(tooManyPaths).contains("menuSelections");
        assertThat(itemPaths).contains("menuSelections[0].quantity");
    }

    @Test
    @DisplayName("반복 메뉴 선택은 메뉴별 수량을 합산한다")
    void normalizesRepeatedMenuSelectionsByMenuId() {
        // given
        ReservationCreateRequest request = validRequest("1", List.of(
                new ReservationMenuSelectionRequest("2", 1),
                new ReservationMenuSelectionRequest("1", 3),
                new ReservationMenuSelectionRequest("2", 2)
        ));

        // when
        List<ReservationMenuSelectionRequest> normalized = request.normalizedMenuSelections();

        // then
        assertThat(normalized).containsExactly(
                new ReservationMenuSelectionRequest("2", 3),
                new ReservationMenuSelectionRequest("1", 3)
        );
    }

    private static ReservationCreateRequest validRequest(
            String storeId,
            List<ReservationMenuSelectionRequest> menuSelections
    ) {
        return new ReservationCreateRequest(
                storeId,
                LocalDate.of(2026, 8, 7),
                LocalTime.of(18, 0),
                "+09:00",
                new ReservationPartyRequest(1, 0, 0),
                menuSelections
        );
    }

    private static Set<String> propertyPathsOf(ReservationCreateRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(violation -> publicPath(violation.getPropertyPath().toString()))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String publicPath(String propertyPath) {
        return propertyPath.isBlank() ? "$" : propertyPath;
    }
}
