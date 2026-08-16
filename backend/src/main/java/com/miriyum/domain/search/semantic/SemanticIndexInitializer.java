package com.miriyum.domain.search.semantic;

import com.miriyum.domain.search.config.OpenAiEmbeddingProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 활성 환경에서 Qdrant collection을 준비하되 장애가 애플리케이션 기동을 막지 않게 한다. */
@Component
public class SemanticIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndexInitializer.class);

    private final boolean enabled;
    private final int dimensions;
    private final SemanticMenuIndex index;

    public SemanticIndexInitializer(
            @Value("${miriyum.store-search.semantic.enabled:false}") boolean enabled,
            OpenAiEmbeddingProperties properties,
            SemanticMenuIndex index
    ) {
        this.enabled = enabled;
        this.dimensions = properties.dimensions();
        this.index = index;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (!enabled) {
            return;
        }
        try {
            index.ensureCollection(dimensions);
        } catch (RuntimeException ignored) {
            // 검색 요청은 MySQL 정확 검색으로 폴백하며 운영 재시도/백필로 복구한다.
            log.warn("Semantic index collection initialization failed; exact search remains active");
        }
    }
}
