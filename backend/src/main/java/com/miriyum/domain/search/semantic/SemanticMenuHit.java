package com.miriyum.domain.search.semantic;

/** Qdrant에서 회수한 최소 식별자와 유사도다. */
public record SemanticMenuHit(long menuId, long storeId, int versionNumber, double score) {
}
