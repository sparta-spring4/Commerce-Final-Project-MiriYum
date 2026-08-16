package com.miriyum.domain.search.semantic;

import java.util.List;

/** 검색 문장을 벡터로 변환하는 외부 제공자 경계다. */
public interface TextEmbeddingClient {

    List<Float> embed(String text);
}
