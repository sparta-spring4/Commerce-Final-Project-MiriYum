package com.miriyum.domain.search.semantic;

import java.util.List;

/** MySQL에서 재구축할 수 있는 메뉴 벡터 인덱스 경계다. */
public interface SemanticMenuIndex {

    void ensureCollection(int dimensions);

    void clear();

    List<SemanticMenuHit> search(List<Float> vector, int limit);

    void upsert(SemanticMenuDocument document, List<Float> vector);

    void delete(long menuId);
}
