package com.miriyum.domain.search.expansion;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.HTTP_ERROR;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.MALFORMED_RESPONSE;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.REFUSAL;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.TIMEOUT;
import static com.miriyum.domain.search.expansion.SearchConceptPurpose.MENU_ALTERNATIVE;
import static com.miriyum.domain.search.expansion.SearchConceptPurpose.STORE_SEARCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.miriyum.domain.search.config.OpenAiSearchInterpretationProperties;
import com.miriyum.domain.search.config.SearchInterpretationHttpConfig;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class OpenAiSearchConceptInterpreterTest {

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
    void sendsAbstentionSchemaAndInstructionAndMapsMatchableUsage() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson(successResponse())));
        OpenAiSearchConceptInterpreter interpreter = interpreter(properties(2_000));

        SearchConceptExpansion result = interpreter.interpret(
                new SearchConceptRequest("얼큰한 국물", STORE_SEARCH));

        assertThat(result.concepts()).containsExactly("김치찌개", "찌개");
        assertThat(result.inputTokens()).isEqualTo(130);
        assertThat(result.outputTokens()).isEqualTo(20);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-secret"))
                .withRequestBody(equalToJson("""
                        {
                          "model":"gpt-4o-mini",
                          "messages":[
                            {"role":"system","content":"사용자 원문에 실제 음식, 재료, 맛 또는 조리법 근거가 있는지 먼저 판정하세요. 음식 계열을 안전하게 특정할 수 있으면 interpretation은 MATCHABLE이고 concepts에 짧은 한국어 음식명과 검색 동의어를 최대 8개까지 가장 관련 높은 순서로 작성하세요. 실제 음식 근거가 없으면 interpretation은 NO_FOOD_SIGNAL이고 concepts는 빈 배열입니다. 음식 관련 가능성은 있지만 음식 계열을 안전하게 특정하기 어려우면 interpretation은 AMBIGUOUS이고 concepts는 빈 배열입니다. 은유나 음식과 무관한 표현을 메뉴명으로 바꾸거나 입력에 없는 메뉴명을 발명하지 마세요. MATCHABLE에서는 맛, 재료, 국물 여부, 조리 형태를 종합하되 서로 다른 음식 계열의 후보를 다양하게 제시하고 같은 계열의 표현만 반복하지 마세요. '메뉴명:', '재료:', '맛:', '조리형태:' 같은 라벨이나 설명 문장을 쓰지 마세요. 알레르기, 식이 안전, 재고, 예약 가능 여부를 추론하지 마세요."},
                            {"role":"user","content":"얼큰한 국물"}
                          ],
                          "max_tokens":100,
                          "temperature":0.0,
                          "response_format":{
                            "type":"json_schema",
                            "json_schema":{
                              "name":"search_concepts",
                              "strict":true,
                              "schema":{
                                "type":"object",
                                "additionalProperties":false,
                                "required":["interpretation","concepts"],
                                "properties":{
                                  "interpretation":{
                                    "type":"string",
                                    "enum":["MATCHABLE","AMBIGUOUS","NO_FOOD_SIGNAL"]
                                  },
                                  "concepts":{
                                    "type":"array",
                                    "maxItems":8,
                                    "items":{"type":"string","maxLength":60}
                                  }
                                }
                              }
                            }
                          }
                        }
                        """, true, true)));
    }

    @ParameterizedTest
    @MethodSource("abstentionResponses")
    void discardsConceptsForAbstentionAndPreservesUsage(String interpretation) {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson(successResponse(interpretation))));
        OpenAiSearchConceptInterpreter interpreter = interpreter(properties(2_000));

        SearchConceptExpansion result = interpreter.interpret(
                new SearchConceptRequest("파란 침묵 좌표", STORE_SEARCH));

        assertThat(result.concepts()).isEmpty();
        assertThat(result.inputTokens()).isEqualTo(130);
        assertThat(result.outputTokens()).isEqualTo(20);
    }

    @Test
    void menuAlternativeRequestsConcreteAlternativesAndStandaloneSearchTraits() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath(
                        "$.messages[0].content",
                        com.github.tomakehurst.wiremock.client.WireMock.containing(
                                "정확히 8개")))
                .withRequestBody(matchingJsonPath(
                        "$.response_format.json_schema.schema.properties.concepts.minItems",
                        equalTo("8")))
                .willReturn(okJson(successResponse())));
        OpenAiSearchConceptInterpreter interpreter = interpreter(properties(2_000));

        SearchConceptExpansion result = interpreter.interpret(new SearchConceptRequest(
                "매콤한 철판 닭갈비 고추장 양념에 볶은 닭고기 철판요리 MEAT",
                MENU_ALTERNATIVE));

        assertThat(result.concepts()).containsExactly("김치찌개", "찌개");
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsRefusalAndMalformedResponses(
            String response,
            SearchConceptFailureReason expected
    ) {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson(response)));

        assertFailure(interpreter(properties(2_000)), expected);
    }

    @ParameterizedTest
    @MethodSource("schemaInvalidDocuments")
    void rejectsWrongStructuredOutputTypesAndPreservesUsage(String content) {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson(responseWithContent(content))));

        assertThatThrownBy(() -> interpreter(properties(2_000)).interpret(
                new SearchConceptRequest("얼큰한 국물", STORE_SEARCH)))
                .isInstanceOfSatisfying(SearchConceptProviderException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(MALFORMED_RESPONSE);
                    assertThat(exception.inputTokens()).isEqualTo(130);
                    assertThat(exception.outputTokens()).isEqualTo(20);
                });
    }

    @Test
    void mapsHttpFailureWithoutRetry() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.status(429)));

        assertFailure(interpreter(properties(2_000)), HTTP_ERROR);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void mapsTimeoutWithoutRetry() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson(successResponse()).withFixedDelay(500)));

        assertFailure(interpreter(properties(100)), TIMEOUT);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    private void assertFailure(
            OpenAiSearchConceptInterpreter interpreter,
            SearchConceptFailureReason expected
    ) {
        assertThatThrownBy(() -> interpreter.interpret(
                new SearchConceptRequest("얼큰한 국물", STORE_SEARCH)))
                .isInstanceOfSatisfying(SearchConceptProviderException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(expected));
    }

    private OpenAiSearchConceptInterpreter interpreter(
            OpenAiSearchInterpretationProperties properties
    ) {
        return new OpenAiSearchConceptInterpreter(
                new SearchInterpretationHttpConfig()
                        .openAiSearchInterpretationRestClient(properties),
                new ObjectMapper(),
                properties);
    }

    private OpenAiSearchInterpretationProperties properties(long responseTimeoutMs) {
        return new OpenAiSearchInterpretationProperties(
                true,
                wireMock.baseUrl(),
                "test-secret",
                "gpt-4o-mini",
                1_000,
                responseTimeoutMs,
                100,
                8,
                200);
    }

    private static Stream<Arguments> invalidResponses() {
        return Stream.of(
                Arguments.of("""
                        {
                          "choices":[{"finish_reason":"stop","message":{
                            "content":null,"refusal":"정책상 거절"
                          }}],
                          "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}
                        }
                        """, REFUSAL),
                Arguments.of("""
                        {
                          "choices":[{"finish_reason":"length","message":{
                            "content":"{\\"interpretation\\":\\"MATCHABLE\\",\\"concepts\\":[]}","refusal":null
                          }}],
                          "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}
                        }
                        """, MALFORMED_RESPONSE),
                Arguments.of("""
                        {
                          "choices":[{"finish_reason":"stop","message":{
                            "content":"{not-json","refusal":null
                          }}],
                          "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}
                        }
                        """, MALFORMED_RESPONSE),
                Arguments.of(responseWithContent(
                        "{\"interpretation\":\"UNKNOWN\",\"concepts\":[]}"),
                        MALFORMED_RESPONSE),
                Arguments.of(responseWithContent("{\"concepts\":[]}"),
                        MALFORMED_RESPONSE),
                Arguments.of("{\"choices\":[],\"usage\":null}", MALFORMED_RESPONSE));
    }

    private static String successResponse() {
        return successResponse("MATCHABLE");
    }

    private static Stream<Arguments> abstentionResponses() {
        return Stream.of(
                Arguments.of("AMBIGUOUS"),
                Arguments.of("NO_FOOD_SIGNAL"));
    }

    private static Stream<Arguments> schemaInvalidDocuments() {
        return Stream.of(
                Arguments.of("{\"interpretation\":0,\"concepts\":[]}"),
                Arguments.of("{\"interpretation\":\"MATCHABLE\",\"concepts\":[123]}"));
    }

    private static String successResponse(String interpretation) {
        return responseWithContent(
                "{\"interpretation\":\"" + interpretation
                        + "\",\"concepts\":[\"김치찌개\",\"찌개\"]}");
    }

    private static String responseWithContent(String content) {
        String escapedContent = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {
                  "choices":[{
                    "finish_reason":"stop",
                    "message":{
                      "content":"%s",
                      "refusal":null
                    }
                  }],
                  "usage":{"prompt_tokens":130,"completion_tokens":20,"total_tokens":150}
                }
                """.formatted(escapedContent);
    }
}
