package com.miriyum.domain.search.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.miriyum.domain.search.config.QdrantSemanticSearchProperties;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Qdrant payload에 메뉴·매장·버전 식별자만 저장하는 재구축 가능 인덱스다. */
@Component
public class QdrantSemanticMenuIndex implements SemanticMenuIndex {

    private final RestClient restClient;
    private final QdrantSemanticSearchProperties properties;

    public QdrantSemanticMenuIndex(
            @Qualifier("qdrantSemanticRestClient") RestClient restClient,
            QdrantSemanticSearchProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public void ensureCollection(int dimensions) {
        try {
            request(restClient.put()
                    .uri("/collections/{collection}", properties.collection())
                    .body(Map.of("vectors", Map.of(
                            "size", dimensions,
                            "distance", "Cosine"))))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 409) {
                throw unavailable();
            }
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    @Override
    public void clear() {
        try {
            request(restClient.post()
                    .uri("/collections/{collection}/points/delete", properties.collection())
                    .body(Map.of("filter", Map.of("must", List.of()))))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    @Override
    public List<SemanticMenuHit> search(List<Float> vector, int limit) {
        try {
            QueryResponse response = request(restClient.post()
                    .uri("/collections/{collection}/points/query", properties.collection())
                    .body(new QueryRequest(vector, limit, properties.scoreThreshold(),
                            List.of("menuId", "storeId", "versionNumber"))))
                    .retrieve()
                    .body(QueryResponse.class);
            if (response == null || response.result() == null
                    || response.result().points() == null) {
                throw unavailable();
            }
            return response.result().points().stream().map(this::hit).toList();
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    @Override
    public void upsert(SemanticMenuDocument document, List<Float> vector) {
        try {
            request(restClient.put()
                    .uri("/collections/{collection}/points", properties.collection())
                    .body(Map.of("points", List.of(Map.of(
                            "id", document.menuId(),
                            "vector", vector,
                            "payload", Map.of(
                                    "menuId", document.menuId(),
                                    "storeId", document.storeId(),
                                    "versionNumber", document.versionNumber()))))))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    @Override
    public void delete(long menuId) {
        try {
            request(restClient.post()
                    .uri("/collections/{collection}/points/delete", properties.collection())
                    .body(Map.of("points", List.of(menuId))))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private RestClient.RequestBodySpec request(RestClient.RequestBodySpec request) {
        return properties.apiKey() == null || properties.apiKey().isBlank()
                ? request
                : request.header("api-key", properties.apiKey());
    }

    private SemanticMenuHit hit(QueryPoint point) {
        if (point == null || point.payload() == null || point.score() == null) {
            throw unavailable();
        }
        return new SemanticMenuHit(
                number(point.payload(), "menuId").longValue(),
                number(point.payload(), "storeId").longValue(),
                number(point.payload(), "versionNumber").intValue(),
                point.score());
    }

    private static Number number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number;
        }
        throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("semantic index unavailable");
    }

    private record QueryRequest(
            List<Float> query,
            int limit,
            double score_threshold,
            List<String> with_payload
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryResponse(QueryResult result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryResult(List<QueryPoint> points) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryPoint(Double score, Map<String, Object> payload) {
    }
}
