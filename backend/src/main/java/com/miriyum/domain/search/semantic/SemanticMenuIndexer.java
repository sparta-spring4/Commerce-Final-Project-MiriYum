package com.miriyum.domain.search.semantic;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** MySQL 현재 문서를 임베딩해 Qdrant 인덱스를 갱신하거나 전체 재구축한다. */
@Service
public class SemanticMenuIndexer {

    private final boolean enabled;
    private final SemanticMenuDocumentSource source;
    private final TextEmbeddingClient embeddingClient;
    private final SemanticMenuIndex index;

    public SemanticMenuIndexer(
            @Value("${miriyum.store-search.semantic.enabled:false}") boolean enabled,
            SemanticMenuDocumentSource source,
            TextEmbeddingClient embeddingClient,
            SemanticMenuIndex index
    ) {
        this.enabled = enabled;
        this.source = source;
        this.embeddingClient = embeddingClient;
        this.index = index;
    }

    /** 현재 공개 문서가 없으면 stale point를 제거한다. */
    public void reindex(long menuId) {
        if (!enabled) {
            return;
        }
        source.findCurrent(menuId).ifPresentOrElse(document -> {
                    if (!SemanticTextPolicy.allowsExternalEmbedding(document.text())) {
                        index.delete(menuId);
                        return;
                    }
                    index.upsert(document, embeddingClient.embed(document.text()));
                },
                () -> index.delete(menuId));
    }

    /** 운영 백필에서 MySQL ID seek로 전체 Qdrant 인덱스를 재생성한다. */
    public void rebuildAll(int batchSize) {
        if (!enabled) {
            return;
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        index.clear();
        long afterMenuId = 0L;
        while (true) {
            List<SemanticMenuDocument> documents = source.findBatchAfter(
                    afterMenuId, batchSize);
            if (documents.isEmpty()) {
                return;
            }
            for (SemanticMenuDocument document : documents) {
                if (document.menuId() <= afterMenuId) {
                    throw new IllegalStateException("semantic document batch is not ordered");
                }
                if (SemanticTextPolicy.allowsExternalEmbedding(document.text())) {
                    index.upsert(document, embeddingClient.embed(document.text()));
                } else {
                    index.delete(document.menuId());
                }
                afterMenuId = document.menuId();
            }
        }
    }
}
