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
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/** OpenAI의 구조화 응답을 제공자 중립 검색 개념으로 변환한다. */
@Component
public class OpenAiSearchConceptInterpreter implements SearchConceptInterpreter {

    private static final String SYSTEM_INSTRUCTION =
            "사용자가 실제 등록 메뉴를 찾도록 검색 표현을 짧은 한국어 음식명과 "
                    + "검색 동의어로 변환하세요. 가장 가능성 높은 구체적 메뉴명을 먼저 두고 "
                    + "'메뉴명:', '재료:', '맛:', '조리형태:' 같은 라벨이나 설명 문장을 "
                    + "쓰지 마세요. 예: '얼큰한 국물'은 '김치찌개', '찌개', '매운 국물'. "
                    + "알레르기, 식이 안전, 재고, 예약 가능 여부를 추론하지 마세요.";

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
                    .body(completionRequest(request.text()))
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

    private CompletionRequest completionRequest(String text) {
        Map<String, Object> concepts = Map.of(
                "type", "array",
                "maxItems", properties.maxConcepts(),
                "items", Map.of("type", "string", "maxLength", 60));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("concepts"),
                "properties", Map.of("concepts", concepts));
        return new CompletionRequest(
                properties.model(),
                List.of(
                        new Message("system", SYSTEM_INSTRUCTION),
                        new Message("user", text)),
                properties.maxOutputTokens(),
                new ResponseFormat(
                        "json_schema",
                        new JsonSchema("search_concepts", true, schema)));
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
        Choice choice = response.choices().getFirst();
        if (choice == null || choice.message() == null) {
            throw failure(MALFORMED_RESPONSE);
        }
        if (choice.message().refusal() != null && !choice.message().refusal().isBlank()) {
            throw failure(REFUSAL);
        }
        if (!"stop".equals(choice.finish_reason())
                || choice.message().content() == null
                || choice.message().content().isBlank()) {
            throw failure(MALFORMED_RESPONSE);
        }
        ConceptDocument document = objectMapper.readValue(
                choice.message().content(), ConceptDocument.class);
        if (document == null
                || document.concepts() == null
                || document.concepts().size() > properties.maxConcepts()
                || document.concepts().stream().anyMatch(value -> value == null)) {
            throw failure(MALFORMED_RESPONSE);
        }
        return new SearchConceptExpansion(
                document.concepts(),
                response.usage().prompt_tokens(),
                response.usage().completion_tokens());
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

    private record CompletionRequest(
            String model,
            List<Message> messages,
            int max_tokens,
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

    private record ConceptDocument(List<String> concepts) {
    }
}
