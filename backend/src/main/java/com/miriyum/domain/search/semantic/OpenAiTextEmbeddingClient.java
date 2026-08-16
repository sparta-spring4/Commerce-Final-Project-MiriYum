package com.miriyum.domain.search.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.miriyum.domain.search.config.OpenAiEmbeddingProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** OpenAI embeddings API 응답을 제공자 중립 벡터로 변환한다. */
@Component
public class OpenAiTextEmbeddingClient implements TextEmbeddingClient {

    private final RestClient restClient;
    private final OpenAiEmbeddingProperties properties;

    public OpenAiTextEmbeddingClient(
            @Qualifier("openAiEmbeddingRestClient") RestClient restClient,
            OpenAiEmbeddingProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public List<Float> embed(String text) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()
                || text == null || text.isBlank()) {
            throw unavailable();
        }
        try {
            EmbeddingResponse response = restClient.post()
                    .uri("/v1/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(new EmbeddingRequest(
                            properties.model(), text, "float", properties.dimensions()))
                    .retrieve()
                    .body(EmbeddingResponse.class);
            if (response == null || response.data() == null || response.data().size() != 1
                    || response.data().getFirst() == null
                    || response.data().getFirst().embedding() == null
                    || response.data().getFirst().embedding().size() != properties.dimensions()
                    || response.data().getFirst().embedding().stream().anyMatch(value ->
                            value == null || !Float.isFinite(value))) {
                throw unavailable();
            }
            return List.copyOf(response.data().getFirst().embedding());
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("embedding provider unavailable");
    }

    private record EmbeddingRequest(
            String model,
            String input,
            String encoding_format,
            int dimensions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(List<EmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(List<Float> embedding) {
    }
}
