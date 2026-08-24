package com.miriyum.domain.search.expansion;

import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.HTTP_ERROR;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.MALFORMED_RESPONSE;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.PROVIDER_ERROR;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.REFUSAL;
import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.TIMEOUT;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.miriyum.domain.search.config.OpenAiSearchInterpretationProperties;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** OpenAI의 구조화 응답을 제공자 중립 검색 개념으로 변환한다. */
@Component
public class OpenAiSearchConceptInterpreter implements SearchConceptInterpreter {

    private static final String SYSTEM_INSTRUCTION =
            "사용자 원문에 실제 음식, 재료, 맛 또는 조리법 근거가 있는지 먼저 "
                    + "판정하세요. 음식 계열을 안전하게 특정할 수 있으면 interpretation은 "
                    + "MATCHABLE이고 concepts에 짧은 한국어 음식명과 검색 동의어를 최대 "
                    + "8개까지 가장 관련 높은 순서로 작성하세요. 실제 음식 근거가 없으면 "
                    + "interpretation은 NO_FOOD_SIGNAL이고 concepts는 빈 배열입니다. 음식 "
                    + "관련 가능성은 있지만 음식 계열을 안전하게 특정하기 어려우면 "
                    + "interpretation은 AMBIGUOUS이고 concepts는 빈 배열입니다. 은유나 "
                    + "음식과 무관한 표현을 메뉴명으로 바꾸거나 입력에 없는 메뉴명을 "
                    + "발명하지 마세요. MATCHABLE에서는 맛, 재료, 국물 여부, 조리 형태를 "
                    + "종합하되 서로 다른 음식 계열의 후보를 다양하게 제시하고 같은 "
                    + "계열의 표현만 반복하지 마세요. "
                    + "'메뉴명:', '재료:', '맛:', '조리형태:' 같은 라벨이나 설명 문장을 "
                    + "쓰지 마세요. 알레르기, 식이 안전, 재고, 예약 가능 여부를 "
                    + "추론하지 마세요.";
    private static final String MENU_ALTERNATIVE_INSTRUCTION =
            "원본 메뉴를 먹을 수 없을 때 대신 선택할 만한 메뉴를 찾으세요. "
                    + "맛, 주재료, 국물 여부, 조리 형태를 종합하되 입력에 없는 특정 "
                    + "메뉴 하나로 강제 매핑하지 마세요. interpretation은 MATCHABLE로 "
                    + "작성하고 concepts 배열은 정확히 8개를 "
                    + "가장 관련 높은 순서로 작성하세요. 앞의 4개는 서로 다른 구체적인 "
                    + "대체 메뉴명, 뒤의 4개는 등록 메뉴의 이름이나 설명에서 대조할 수 "
                    + "있는 주재료, 맛, 조리 형태, 넓은 음식 계열의 독립된 짧은 "
                    + "검색어를 차례대로 작성하세요. 같은 표현을 반복하지 말고 '메뉴명:', "
                    + "'재료:', '맛:', '조리형태:' 같은 라벨이나 설명 문장을 쓰지 "
                    + "마세요. 알레르기, 식이 안전, 재고, 예약 가능 여부를 추론하지 "
                    + "마세요.";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OpenAiSearchInterpretationProperties properties;

    public OpenAiSearchConceptInterpreter(
            @Qualifier("openAiSearchInterpretationRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            OpenAiSearchInterpretationProperties properties
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public SearchConceptExpansion interpret(SearchConceptRequest request) {
        try {
            CompletionResponse response = restClient.post()
                    .uri("/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(completionRequest(request))
                    .retrieve()
                    .body(CompletionResponse.class);
            return parse(response);
        } catch (SearchConceptProviderException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw failure(HTTP_ERROR);
        } catch (ResourceAccessException exception) {
            throw new SearchConceptProviderException(
                    isTimeout(exception) ? TIMEOUT : PROVIDER_ERROR,
                    exception);
        } catch (RestClientException exception) {
            throw failure(PROVIDER_ERROR);
        } catch (RuntimeException exception) {
            throw failure(MALFORMED_RESPONSE);
        }
    }

    private CompletionRequest completionRequest(SearchConceptRequest request) {
        Map<String, Object> concepts = request.purpose() == SearchConceptPurpose.MENU_ALTERNATIVE
                ? Map.of(
                        "type", "array",
                        "minItems", properties.maxConcepts(),
                        "maxItems", properties.maxConcepts(),
                        "items", Map.of("type", "string", "maxLength", 60))
                : Map.of(
                        "type", "array",
                        "maxItems", properties.maxConcepts(),
                        "items", Map.of("type", "string", "maxLength", 60));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("interpretation", "concepts"),
                "properties", Map.of(
                        "interpretation", Map.of(
                                "type", "string",
                                "enum", List.of(
                                        "MATCHABLE",
                                        "AMBIGUOUS",
                                        "NO_FOOD_SIGNAL")),
                        "concepts", concepts));
        return new CompletionRequest(
                properties.model(),
                List.of(
                        new Message("system", instructionFor(request.purpose())),
                        new Message("user", request.text())),
                properties.maxOutputTokens(),
                0.0,
                new ResponseFormat(
                        "json_schema",
                        new JsonSchema("search_concepts", true, schema)));
    }

    private static String instructionFor(SearchConceptPurpose purpose) {
        return purpose == SearchConceptPurpose.MENU_ALTERNATIVE
                ? MENU_ALTERNATIVE_INSTRUCTION
                : SYSTEM_INSTRUCTION;
    }

    private SearchConceptExpansion parse(CompletionResponse response) {
        if (response == null
                || response.choices() == null
                || response.choices().size() != 1
                || response.usage() == null
                || response.usage().prompt_tokens() < 0
                || response.usage().completion_tokens() < 0) {
            throw failure(MALFORMED_RESPONSE);
        }
        long inputTokens = response.usage().prompt_tokens();
        long outputTokens = response.usage().completion_tokens();
        Choice choice = response.choices().getFirst();
        if (choice == null || choice.message() == null) {
            throw failure(MALFORMED_RESPONSE, inputTokens, outputTokens);
        }
        if (choice.message().refusal() != null && !choice.message().refusal().isBlank()) {
            throw failure(REFUSAL, inputTokens, outputTokens);
        }
        if (!"stop".equals(choice.finish_reason())
                || choice.message().content() == null
                || choice.message().content().isBlank()) {
            throw failure(MALFORMED_RESPONSE, inputTokens, outputTokens);
        }
        ConceptDocument document;
        try {
            document = readConceptDocument(choice.message().content());
        } catch (RuntimeException exception) {
            throw failure(MALFORMED_RESPONSE, inputTokens, outputTokens);
        }
        if (document == null
                || document.interpretation() == null
                || document.concepts() == null
                || document.concepts().size() > properties.maxConcepts()
                || document.concepts().stream().anyMatch(value -> value == null)) {
            throw failure(MALFORMED_RESPONSE, inputTokens, outputTokens);
        }
        return new SearchConceptExpansion(
                document.interpretation() == Interpretation.MATCHABLE
                        ? document.concepts()
                        : List.of(),
                response.usage().prompt_tokens(),
                response.usage().completion_tokens());
    }

    private ConceptDocument readConceptDocument(String content) {
        JsonNode root = objectMapper.readTree(content);
        if (root == null || !root.isObject() || root.size() != 2) {
            throw new IllegalArgumentException("concept document must match the schema");
        }
        JsonNode interpretationNode = root.get("interpretation");
        JsonNode conceptsNode = root.get("concepts");
        if (interpretationNode == null
                || !interpretationNode.isTextual()
                || conceptsNode == null
                || !conceptsNode.isArray()) {
            throw new IllegalArgumentException("concept document fields have invalid types");
        }
        Interpretation interpretation = Interpretation.valueOf(interpretationNode.asText());
        List<String> concepts = new ArrayList<>();
        for (JsonNode conceptNode : conceptsNode) {
            if (!conceptNode.isTextual()) {
                throw new IllegalArgumentException("concept must be a string");
            }
            concepts.add(conceptNode.asText());
        }
        return new ConceptDocument(interpretation, List.copyOf(concepts));
    }

    private static boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static SearchConceptProviderException failure(
            SearchConceptFailureReason reason
    ) {
        return new SearchConceptProviderException(reason);
    }

    private static SearchConceptProviderException failure(
            SearchConceptFailureReason reason,
            long inputTokens,
            long outputTokens
    ) {
        return new SearchConceptProviderException(reason, inputTokens, outputTokens);
    }

    private record CompletionRequest(
            String model,
            List<Message> messages,
            int max_tokens,
            double temperature,
            ResponseFormat response_format
    ) {
    }

    private record Message(String role, String content) {
    }

    private record ResponseFormat(String type, JsonSchema json_schema) {
    }

    private record JsonSchema(
            String name,
            boolean strict,
            Map<String, Object> schema
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CompletionResponse(List<Choice> choices, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(String finish_reason, CompletionMessage message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CompletionMessage(String content, String refusal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(long prompt_tokens, long completion_tokens, long total_tokens) {
    }

    private record ConceptDocument(Interpretation interpretation, List<String> concepts) {
    }

    private enum Interpretation {
        MATCHABLE,
        AMBIGUOUS,
        NO_FOOD_SIGNAL
    }
}
