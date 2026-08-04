package com.miriyum.domain.store.core.service;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.miriyum.domain.store.core.config.StoreGeocodingConfig;
import com.miriyum.domain.store.core.config.StoreGeocodingProperties;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.model.StoreGeocodingResult;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

class KakaoLocalGeocodingAdapterTest {

    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    @Test
    @DisplayName("Kakao 인증·검색 제한을 적용하고 응답을 제공자 중립 후보로 변환한다")
    void mapsSuccessfulKakaoAddressSearch() {
        wireMock.stubFor(get(urlPathEqualTo("/v2/local/search/address.json"))
                .willReturn(okJson(successResponse())));
        StoreGeocodingProperties properties = properties("test-key", 1_000, 2_000);
        RestClient restClient = new StoreGeocodingConfig()
                .storeGeocodingRestClient(properties);
        KakaoLocalGeocodingAdapter adapter =
                new KakaoLocalGeocodingAdapter(restClient, properties);

        StoreGeocodingResult result = adapter.geocode("서울 중구 세종대로 110");

        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.provider()).isEqualTo("KAKAO_LOCAL");
        assertThat(result.providerApiVersion()).isEqualTo("v2");
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.roadAddress()).isEqualTo("서울 중구 세종대로 110");
            assertThat(candidate.parcelAddress()).isEqualTo("서울 중구 태평로1가 31");
            assertThat(candidate.region1DepthName()).isEqualTo("서울");
            assertThat(candidate.longitude()).isEqualTo("126.978656700000000");
            assertThat(candidate.latitude()).isEqualTo("37.566826000000000");
        });
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v2/local/search/address.json"))
                .withQueryParam("query", equalTo("서울 중구 세종대로 110"))
                .withQueryParam("analyze_type", equalTo("similar"))
                .withQueryParam("size", equalTo("2"))
                .withHeader("Authorization", equalTo("KakaoAK test-key")));
    }

    @Test
    @DisplayName("API key가 비어 있으면 외부 요청 없이 서비스 일시 불가로 거부한다")
    void rejectsEmptyApiKeyWithoutRequest() {
        KakaoLocalGeocodingAdapter adapter = adapter(properties(" ", 1_000, 2_000));

        assertServiceUnavailable(() -> adapter.geocode("서울 중구 세종대로 110"));

        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/v2/local/search/address.json")));
    }

    @ParameterizedTest
    @MethodSource("providerHttpFailures")
    @DisplayName("Kakao 인증·호출 제한·서버 오류는 서비스 일시 불가로 변환한다")
    void mapsProviderHttpFailureToServiceUnavailable(int status) {
        wireMock.stubFor(get(urlPathEqualTo("/v2/local/search/address.json"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                        .status(status)
                        .withBody("provider-body-must-not-escape")));
        KakaoLocalGeocodingAdapter adapter = adapter(properties("test-key", 1_000, 2_000));

        assertServiceUnavailable(() -> adapter.geocode("서울 중구 세종대로 110"));

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/v2/local/search/address.json")));
    }

    @Test
    @DisplayName("응답 제한 시간을 넘겨도 재시도하지 않고 서비스 일시 불가로 변환한다")
    void mapsTimeoutWithoutRetry() {
        wireMock.stubFor(get(urlPathEqualTo("/v2/local/search/address.json"))
                .willReturn(okJson(successResponse()).withFixedDelay(500)));
        KakaoLocalGeocodingAdapter adapter = adapter(properties("test-key", 1_000, 100));

        assertServiceUnavailable(() -> adapter.geocode("서울 중구 세종대로 110"));

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/v2/local/search/address.json")));
    }

    @Test
    @DisplayName("연결 실패는 서비스 일시 불가로 변환한다")
    void mapsConnectionFailure() {
        KakaoLocalGeocodingAdapter adapter = adapter(properties("test-key", 100, 100));
        wireMock.stop();

        assertServiceUnavailable(() -> adapter.geocode("서울 중구 세종대로 110"));
    }

    @ParameterizedTest
    @MethodSource("malformedProviderResponses")
    @DisplayName("JSON 또는 필수 envelope를 해석할 수 없으면 서비스 일시 불가로 변환한다")
    void mapsMalformedResponseToServiceUnavailable(String body) {
        wireMock.stubFor(get(urlPathEqualTo("/v2/local/search/address.json"))
                .willReturn(okJson(body)));
        KakaoLocalGeocodingAdapter adapter = adapter(properties("test-key", 1_000, 2_000));

        assertServiceUnavailable(() -> adapter.geocode("서울 중구 세종대로 110"));
    }

    @ParameterizedTest
    @MethodSource("semanticValidationResponses")
    @DisplayName("해석 가능한 후보 수·좌표 오류는 validator가 공통 입력 검증 오류로 분류한다")
    void preservesSemanticFailuresForValidator(String responseBody) {
        wireMock.stubFor(get(urlPathEqualTo("/v2/local/search/address.json"))
                .willReturn(okJson(responseBody)));
        KakaoLocalGeocodingAdapter adapter = adapter(properties("test-key", 1_000, 2_000));
        StoreGeocodingValidator validator = new StoreGeocodingValidator();

        StoreGeocodingResult result = adapter.geocode("서울 중구 세종대로 110");

        assertThatThrownBy(() -> validator.validate(
                Region.SEOUL,
                "서울 중구 세종대로 110",
                result,
                Instant.parse("2026-08-04T09:00:00Z")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @ParameterizedTest
    @MethodSource("invalidTimeoutProperties")
    @DisplayName("0 이하의 연결·응답 제한 시간은 명시적인 설정 오류로 거부한다")
    void rejectsNonPositiveTimeouts(
            long connectTimeoutMs,
            long responseTimeoutMs,
            String expectedMessage
    ) {
        StoreGeocodingProperties properties = properties(
                "test-key",
                connectTimeoutMs,
                responseTimeoutMs);

        assertThatThrownBy(() -> new StoreGeocodingConfig()
                .storeGeocodingRestClient(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
    }

    private static Stream<Arguments> providerHttpFailures() {
        return Stream.of(
                Arguments.of(HttpStatus.UNAUTHORIZED.value()),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS.value()),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    private static Stream<Arguments> malformedProviderResponses() {
        return Stream.of(
                Arguments.of("{not-json"),
                Arguments.of("{\"documents\": []}"),
                Arguments.of("{\"meta\": {\"total_count\": 0}}"),
                Arguments.of("""
                        {
                          "meta": {"total_count": 1},
                          "documents": [null]
                        }
                        """));
    }

    private static Stream<Arguments> semanticValidationResponses() {
        String validDocument = candidateDocument(
                "37.566826000000000",
                "126.978656700000000");
        String outOfRangeDocument = candidateDocument(
                "91.000000000000000",
                "126.978656700000000");
        return Stream.of(
                Arguments.of(response(0, "")),
                Arguments.of(response(2, validDocument + "," + validDocument)),
                Arguments.of(response(1, outOfRangeDocument)));
    }

    private static Stream<Arguments> invalidTimeoutProperties() {
        return Stream.of(
                Arguments.of(0L, 2_000L, "geocoding connect timeout must be positive"),
                Arguments.of(-1L, 2_000L, "geocoding connect timeout must be positive"),
                Arguments.of(1_000L, 0L, "geocoding response timeout must be positive"),
                Arguments.of(1_000L, -1L, "geocoding response timeout must be positive"));
    }

    private KakaoLocalGeocodingAdapter adapter(StoreGeocodingProperties properties) {
        RestClient restClient = new StoreGeocodingConfig()
                .storeGeocodingRestClient(properties);
        return new KakaoLocalGeocodingAdapter(restClient, properties);
    }

    private void assertServiceUnavailable(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    private StoreGeocodingProperties properties(
            String apiKey,
            long connectTimeoutMs,
            long responseTimeoutMs
    ) {
        return new StoreGeocodingProperties(
                wireMock.baseUrl(),
                apiKey,
                connectTimeoutMs,
                responseTimeoutMs);
    }

    private String successResponse() {
        return response(1, candidateDocument(
                "37.566826000000000",
                "126.978656700000000"));
    }

    private static String response(int totalCount, String documents) {
        return """
                {
                  "meta": {
                    "total_count": %d,
                    "pageable_count": %d,
                    "is_end": true
                  },
                  "documents": [%s]
                }
                """.formatted(totalCount, totalCount, documents);
    }

    private static String candidateDocument(String latitude, String longitude) {
        return """
                    {
                      "address_name": "서울 중구 태평로1가 31",
                      "address_type": "ROAD_ADDR",
                      "x": "%s",
                      "y": "%s",
                      "address": {
                        "address_name": "서울 중구 태평로1가 31",
                        "region_1depth_name": "서울",
                        "region_2depth_name": "중구",
                        "region_3depth_name": "태평로1가"
                      },
                      "road_address": {
                        "address_name": "서울 중구 세종대로 110",
                        "region_1depth_name": "서울",
                        "region_2depth_name": "중구",
                        "region_3depth_name": "태평로1가",
                        "road_name": "세종대로",
                        "main_building_no": "110"
                      }
                    }
                """.formatted(longitude, latitude);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
