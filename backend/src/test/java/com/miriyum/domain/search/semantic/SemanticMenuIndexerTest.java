package com.miriyum.domain.search.semantic;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SemanticMenuIndexerTest {

    @Mock SemanticMenuDocumentSource source;
    @Mock TextEmbeddingClient embeddingClient;
    @Mock SemanticMenuIndex index;

    @Test
    void upsertsCurrentMysqlDocument() {
        SemanticMenuDocument document = new SemanticMenuDocument(
                11L, 2L, 3, "김치찌개 얼큰한 국물 한식 찌개");
        given(source.findCurrent(11L)).willReturn(Optional.of(document));
        given(embeddingClient.embed(document.text())).willReturn(List.of(0.1f, 0.2f));

        new SemanticMenuIndexer(true, source, embeddingClient, index).reindex(11L);

        then(index).should().upsert(document, List.of(0.1f, 0.2f));
    }

    @Test
    void deletesPointWhenMenuIsNoLongerSearchable() {
        given(source.findCurrent(11L)).willReturn(Optional.empty());

        new SemanticMenuIndexer(true, source, embeddingClient, index).reindex(11L);

        then(index).should().delete(11L);
        then(embeddingClient).shouldHaveNoInteractions();
    }

    @Test
    void deletesPointWithoutEmbeddingWhenMenuDocumentContainsContactInformation() {
        SemanticMenuDocument document = new SemanticMenuDocument(
                11L, 2L, 3, "김치찌개 문의 010-1234-5678");
        given(source.findCurrent(11L)).willReturn(Optional.of(document));

        new SemanticMenuIndexer(true, source, embeddingClient, index).reindex(11L);

        then(index).should().delete(11L);
        then(embeddingClient).shouldHaveNoInteractions();
    }

    @Test
    void rebuildsAllDocumentsFromMysqlInBatches() {
        SemanticMenuDocument first = new SemanticMenuDocument(11L, 2L, 3, "김치찌개");
        SemanticMenuDocument second = new SemanticMenuDocument(15L, 4L, 2, "부대찌개");
        given(source.findBatchAfter(0L, 100)).willReturn(List.of(first, second));
        given(source.findBatchAfter(15L, 100)).willReturn(List.of());
        given(embeddingClient.embed(first.text())).willReturn(List.of(0.1f));
        given(embeddingClient.embed(second.text())).willReturn(List.of(0.2f));

        new SemanticMenuIndexer(true, source, embeddingClient, index).rebuildAll(100);

        then(index).should().clear();
        then(index).should().upsert(first, List.of(0.1f));
        then(index).should().upsert(second, List.of(0.2f));
    }
}
