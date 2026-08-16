package com.miriyum.domain.search.semantic;

import com.miriyum.domain.menu.service.MenuCommandService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Duration;
import java.time.Instant;

/** 메뉴 커밋 성공 뒤에만 비동기로 색인하며 일시 실패를 제한 재시도한다. */
@Component
public class MenuSemanticIndexListener {

    private static final int MAX_ATTEMPTS = 3;

    private final SemanticMenuIndexer indexer;
    private final MeterRegistry meterRegistry;

    public MenuSemanticIndexListener(SemanticMenuIndexer indexer) {
        this(indexer, null);
    }

    @Autowired
    public MenuSemanticIndexListener(
            SemanticMenuIndexer indexer,
            MeterRegistry meterRegistry
    ) {
        this.indexer = indexer;
        this.meterRegistry = meterRegistry;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void reindex(MenuCommandService.SemanticIndexChanged event) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                indexer.reindex(event.menuId());
                recordLag(event);
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                increment("miriyum.search.semantic.index.retry");
            }
        }
        increment("miriyum.search.semantic.index.failure");
        throw new IllegalStateException("semantic menu indexing failed", lastFailure);
    }

    private void increment(String name) {
        if (meterRegistry != null) {
            meterRegistry.counter(name).increment();
        }
    }

    private void recordLag(MenuCommandService.SemanticIndexChanged event) {
        if (meterRegistry == null) {
            return;
        }
        Duration lag = Duration.between(event.signaledAt(), Instant.now());
        meterRegistry.timer("miriyum.search.semantic.index.lag")
                .record(lag.isNegative() ? Duration.ZERO : lag);
    }
}
