package com.miriyum.domain.search.semantic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** MySQL 정본을 주기적으로 다시 읽어 누락·실패한 Qdrant 색인을 복구한다. */
@Component
@ConditionalOnProperty(
        name = "miriyum.store-search.semantic.rebuild-enabled",
        havingValue = "true")
public class SemanticIndexRebuildJob {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndexRebuildJob.class);

    private final SemanticMenuIndexer indexer;
    private final int batchSize;

    public SemanticIndexRebuildJob(
            SemanticMenuIndexer indexer,
            @Value("${miriyum.store-search.semantic.rebuild-batch-size:100}") int batchSize
    ) {
        this.indexer = indexer;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${miriyum.store-search.semantic.rebuild-delay-ms:21600000}",
            initialDelayString = "${miriyum.store-search.semantic.rebuild-initial-delay-ms:60000}")
    public void rebuild() {
        try {
            indexer.rebuildAll(batchSize);
        } catch (RuntimeException exception) {
            log.warn("Semantic index rebuild failed; next scheduled run will retry");
        }
    }
}
