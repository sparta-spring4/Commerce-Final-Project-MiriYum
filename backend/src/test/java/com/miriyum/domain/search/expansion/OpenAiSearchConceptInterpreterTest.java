package com.miriyum.domain.search.expansion;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.EvidenceTerm;
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
    void sendsStrictFoodDimensionSchemaAndMapsMatchableUsage() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson(storeSearchSuccessResponse())));
        OpenAiSearchConceptInterpreter interpreter = interpreter(properties(2_000));

        SearchConceptExpansion result = interpreter.interpret(
                new SearchConceptRequest("얼큰한 국물", STORE_SEARCH));

        assertThat(result.concepts()).containsExactly("김치찌개", "찌개");
        assertThat(result.foodEvidence().rawFoodSpans())
                .extracting(EvidenceTerm::surface)
                .containsExactly("얼큰한 국물");
        assertThat(result.foodEvidence().menuFamilies())
                .extracting(EvidenceTerm::surface)
                .containsExactly("김치찌개");
        assertThat(result.foodEvidence().tastes())
                .extracting(EvidenceTerm::surface)
                .containsExactly("얼큰한");
        assertThat(result.foodEvidence().broths())
                .extracting(EvidenceTerm::surface)
                .containsExactly("국물");
        assertThat(result.foodEvidence().menuFamilies())
                .extracting(EvidenceTerm::source)
                .containsOnly(StructuredFoodEvidenceSource.LLM);
        assertThat(result.inputTokens()).isEqualTo(130);
        assertThat(result.outputTokens()).isEqualTo(20);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-secret"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-4o-mini")))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.0")))
                .withRequestBody(matchingJsonPath(
                        "$.response_format.json_schema.strict", equalTo("true")))
                .withRequestBody(matchingJsonPath(
                        "$.response_format.json_schema.schema.additionalProperties",
                        equalTo("false")))
                .withRequestBody(matchingJsonPath(
                        "$.response_format.json_schema.schema.properties.menuFamilies.type",
                        equalTo("array")))
                .withRequestBody(matchingJsonPath(
                        "$.response_format.json_schema.schema.properties.ingredients.type",
                        equalTo("array")))
                .withRequestBody(matchingJsonPath(
                        "$.response_format.json_schema.schema.properties.tastes.type",
                        equalTo("array")))
                .withRequestBody(matchingJsonPath(
                        "$.response_format.json_schema.schema.properties.aromas.type",
                        equalTo("array")))
                .withRequestBody(matchingJsonPath(
                        "$.response_format.json_schema.schema.required[10]",
                        equalTo("forms"))));
    }

    @ParameterizedTest
    @MethodSource("abstentionResponses")
    void discardsConceptsForAbstentionAndPreservesUsage(String interpretation) {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson(storeSearchSuccessResponse(interpretation))));
        OpenAiSearchConceptInterpreter interpreter = interpreter(properties(2_000));

        SearchConceptExpansion result = interpreter.interpret(
                new SearchConceptRequest("파란 침묵 좌표", STORE_SEARCH));

        assertThat(result.concepts()).isEmpty();
        assertThat(result.foodEvidence()).isEqualTo(StructuredFoodEvidence.empty());
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
                .willReturn(okJson(menuAlternativeSuccessResponse())));
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
                .willReturn(okJson(storeSearchSuccessResponse()).withFixedDelay(500)));

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

    private static String storeSearchSuccessResponse() {
        return storeSearchSuccessResponse("MATCHABLE");
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

    private static String storeSearchSuccessResponse(String interpretation) {
        return responseWithContent("""
                {"interpretation":"%s","concepts":["김치찌개","찌개"],
                 "rawFoodSpans":["얼큰한 국물"],"menuFamilies":["김치찌개"],
                 "ingredients":[],"tastes":["얼큰한"],"broths":["국물"],
                 "methods":[],"aromas":[],"textures":[],"forms":[]}
                """.formatted(interpretation).replaceAll("\\s+", " "));
    }

    private static String menuAlternativeSuccessResponse() {
        return responseWithContent(
                "{\"interpretation\":\"MATCHABLE\","
                        + "\"concepts\":[\"김치찌개\",\"찌개\"]}");
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
