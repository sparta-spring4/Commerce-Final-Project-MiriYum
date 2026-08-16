package com.miriyum.domain.search.semantic;

import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 외부 의미 검색 장애를 정확 MySQL 검색의 빈 보완 결과로 격리한다. */
@Service
public class SemanticMenuSearchService {

    private static final int MAX_SEMANTIC_CANDIDATES = 200;

    private final boolean enabled;
    private final TextEmbeddingClient embeddingClient;
    private final SemanticMenuIndex index;
    private final MeterRegistry meterRegistry;

    public SemanticMenuSearchService(
            @Value("${miriyum.store-search.semantic.enabled:false}") boolean enabled,
            TextEmbeddingClient embeddingClient,
            SemanticMenuIndex index
    ) {
        this(enabled, embeddingClient, index, null);
    }

    @Autowired
    public SemanticMenuSearchService(
            @Value("${miriyum.store-search.semantic.enabled:false}") boolean enabled,
            TextEmbeddingClient embeddingClient,
            SemanticMenuIndex index,
            MeterRegistry meterRegistry
    ) {
        this.enabled = enabled;
        this.embeddingClient = embeddingClient;
        this.index = index;
        this.meterRegistry = meterRegistry;
    }

    /** 정규화된 잔여 표현만 외부 경계로 보내고 실패하면 후보 없음으로 폴백한다. */
    public List<SemanticMenuHit> search(String expression, int limit) {
        if (!enabled || limit < 1
                || !SemanticTextPolicy.allowsExternalEmbedding(expression)) {
            return List.of();
        }
        int resolvedLimit = Math.min(limit, MAX_SEMANTIC_CANDIDATES);
        Timer.Sample sample = meterRegistry == null ? null : Timer.start(meterRegistry);
        try {
            List<Float> vector = embeddingClient.embed(expression);
            if (vector == null || vector.isEmpty()) {
                return List.of();
            }
            List<SemanticMenuHit> hits = index.search(vector, resolvedLimit);
            increment("miriyum.search.semantic.success");
            return hits == null
                    ? List.of()
                    : hits.stream().limit(resolvedLimit).toList();
        } catch (RuntimeException exception) {
            increment("miriyum.search.semantic.fallback");
            return List.of();
        } finally {
            if (sample != null) {
                sample.stop(meterRegistry.timer("miriyum.search.semantic.latency"));
            }
        }
    }

    private void increment(String name) {
        if (meterRegistry != null) {
            meterRegistry.counter(name).increment();
        }
    }
}
