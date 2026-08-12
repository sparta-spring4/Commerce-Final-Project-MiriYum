package com.miriyum.domain.search.interpreter;

/**
 * 원화 가격의 최소·최대 포함 경계다.
 *
 * @param minInclusive 최소 포함 가격, 하한이 없으면 {@code null}
 * @param maxInclusive 최대 포함 가격, 상한이 없으면 {@code null}
 */
public record PriceRange(Long minInclusive, Long maxInclusive) {

    public PriceRange {
        if (minInclusive == null && maxInclusive == null) {
            throw new IllegalArgumentException("at least one price bound is required");
        }
        if (minInclusive != null && minInclusive < 0) {
            throw new IllegalArgumentException("minInclusive must not be negative");
        }
        if (maxInclusive != null && maxInclusive < 0) {
            throw new IllegalArgumentException("maxInclusive must not be negative");
        }
        if (minInclusive != null && maxInclusive != null && minInclusive > maxInclusive) {
            throw new IllegalArgumentException("minInclusive must not exceed maxInclusive");
        }
    }
}
