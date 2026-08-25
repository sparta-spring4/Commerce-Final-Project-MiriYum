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
import java.util.LinkedHashMap;
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
                    + "판정하세요. MATCHABLE이면 rawFoodSpans, menuFamilies, ingredients, "
                    + "tastes, broths, methods, aromas, textures, forms를 각각 짧은 한국어 "
                    + "표현으로 분리하고 concepts에는 검색 동의어를 최대 8개까지 작성하세요. "
                    + "칼칼한 마라탕은 menuFamilies에 마라탕, tastes에 칼칼한을 넣고 수식어를 "
                    + "메뉴명의 일부로 강제하지 마세요. 실제 음식 근거가 없으면 "
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
            return parse(response, request.purpose());
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
        Map<String, Object> schemaProperties = new LinkedHashMap<>();
        schemaProperties.put("interpretation", Map.of(
                "type", "string",
                "enum", List.of("MATCHABLE", "AMBIGUOUS", "NO_FOOD_SIGNAL")));
        schemaProperties.put("concepts", concepts);
        List<String> required = new ArrayList<>(List.of("interpretation", "concepts"));
        if (request.purpose() == SearchConceptPurpose.STORE_SEARCH) {
            for (String field : List.of(
                    "rawFoodSpans",
                    "menuFamilies",
                    "ingredients",
                    "tastes",
                    "broths",
                    "methods",
                    "aromas",
                    "textures",
                    "forms")) {
                schemaProperties.put(field, stringArraySchema());
                required.add(field);
            }
        }
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.copyOf(required),
                "properties", schemaProperties);
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

    private Map<String, Object> stringArraySchema() {
        return Map.of(
                "type", "array",
                "maxItems", properties.maxConcepts(),
                "items", Map.of("type", "string", "maxLength", 60));
    }

    private static String instructionFor(SearchConceptPurpose purpose) {
        return purpose == SearchConceptPurpose.MENU_ALTERNATIVE
                ? MENU_ALTERNATIVE_INSTRUCTION
                : SYSTEM_INSTRUCTION;
    }

    private SearchConceptExpansion parse(
            CompletionResponse response,
            SearchConceptPurpose purpose
    ) {
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
            document = readConceptDocument(choice.message().content(), purpose);
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
        boolean matchable = document.interpretation() == Interpretation.MATCHABLE;
        return new SearchConceptExpansion(
                matchable ? document.concepts() : List.of(),
                matchable ? document.toEvidence() : StructuredFoodEvidence.empty(),
                response.usage().prompt_tokens(),
                response.usage().completion_tokens());
    }

    private ConceptDocument readConceptDocument(
            String content,
            SearchConceptPurpose purpose
    ) {
        JsonNode root = objectMapper.readTree(content);
        int expectedFields = purpose == SearchConceptPurpose.STORE_SEARCH ? 11 : 2;
        if (root == null || !root.isObject() || root.size() != expectedFields) {
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
        List<String> concepts = readStringArray(conceptsNode, "concepts");
        if (purpose == SearchConceptPurpose.MENU_ALTERNATIVE) {
            return ConceptDocument.conceptsOnly(interpretation, concepts);
        }
        return new ConceptDocument(
                interpretation,
                concepts,
                readRequiredArray(root, "rawFoodSpans"),
                readRequiredArray(root, "menuFamilies"),
                readRequiredArray(root, "ingredients"),
                readRequiredArray(root, "tastes"),
                readRequiredArray(root, "broths"),
                readRequiredArray(root, "methods"),
                readRequiredArray(root, "aromas"),
                readRequiredArray(root, "textures"),
                readRequiredArray(root, "forms"));
    }

    private static List<String> readRequiredArray(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return readStringArray(node, field);
    }

    private static List<String> readStringArray(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException(field + " must contain strings");
            }
            values.add(value.asText());
        }
        return List.copyOf(values);
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

    private record ConceptDocument(
            Interpretation interpretation,
            List<String> concepts,
            List<String> rawFoodSpans,
            List<String> menuFamilies,
            List<String> ingredients,
            List<String> tastes,
            List<String> broths,
            List<String> methods,
            List<String> aromas,
            List<String> textures,
            List<String> forms
    ) {
        private static ConceptDocument conceptsOnly(
                Interpretation interpretation,
                List<String> concepts
        ) {
            return new ConceptDocument(
                    interpretation,
                    concepts,
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
        }

        private StructuredFoodEvidence toEvidence() {
            return new StructuredFoodEvidence(
                    evidenceTerms("RAW", rawFoodSpans),
                    evidenceTerms("MENU", menuFamilies),
                    evidenceTerms("INGREDIENT", ingredients),
                    evidenceTerms("TASTE", tastes),
                    evidenceTerms("BROTH", broths),
                    evidenceTerms("METHOD", methods),
                    evidenceTerms("AROMA", aromas),
                    evidenceTerms("TEXTURE", textures),
                    evidenceTerms("FORM", forms));
        }

        private static List<StructuredFoodEvidence.EvidenceTerm> evidenceTerms(
                String prefix,
                List<String> values
        ) {
            List<StructuredFoodEvidence.EvidenceTerm> terms = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index);
                terms.add(new StructuredFoodEvidence.EvidenceTerm(
                        "LLM_" + prefix + "_" + index,
                        value,
                        List.of(value),
                        StructuredFoodEvidenceSource.LLM));
            }
            return List.copyOf(terms);
        }
    }

    private enum Interpretation {
        MATCHABLE,
        AMBIGUOUS,
        NO_FOOD_SIGNAL
    }
}
