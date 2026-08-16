package com.miriyum.domain.search.semantic;

import java.util.List;
import java.util.Optional;

/** MySQL 정본에서 현재 검색 가능한 메뉴 문서를 읽는 경계다. */
public interface SemanticMenuDocumentSource {

    Optional<SemanticMenuDocument> findCurrent(long menuId);

    List<SemanticMenuDocument> findBatchAfter(long menuId, int limit);
}
