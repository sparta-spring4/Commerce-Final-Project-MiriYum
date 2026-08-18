package com.miriyum.domain.search.expansion;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.HTTP_ERROR;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.MALFORMED_RESPONSE;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.REFUSAL;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.TIMEOUT;
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
    void sendsStrictBoundedSchemaAndMapsUsage() {
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
                            {"role":"system","content":"검색 표현을 한국어 메뉴명, 재료, 맛, 조리형태 개념으로만 변환하세요. 알레르기, 식이 안전, 재고, 예약 가능 여부를 추론하지 마세요."},
                            {"role":"user","content":"얼큰한 국물"}
                          ],
                          "max_tokens":100,
                          "response_format":{
                            "type":"json_schema",
                            "json_schema":{
                              "name":"search_concepts",
                              "strict":true,
                              "schema":{
                                "type":"object",
                                "additionalProperties":false,
                                "required":["concepts"],
                                "properties":{
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
    @MethodSource("invalidResponses")
    void rejectsRefusalAndMalformedResponses(
            String response,
            SearchConceptFailureReason expected
    ) {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson(response)));

        assertFailure(interpreter(properties(2_000)), expected);
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
                            "content":"{\\"concepts\\":[]}","refusal":null
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
                Arguments.of("{\"choices\":[],\"usage\":null}", MALFORMED_RESPONSE));
    }

    private static String successResponse() {
        return """
                {
                  "choices":[{
                    "finish_reason":"stop",
                    "message":{
                      "content":"{\\"concepts\\":[\\"김치찌개\\",\\"찌개\\"]}",
                      "refusal":null
                    }
                  }],
                  "usage":{"prompt_tokens":130,"completion_tokens":20,"total_tokens":150}
                }
                """;
    }
}
