package com.miriyum.domain.search.expansion;

import java.util.Objects;

/** 제공자에게 전달 가능한 최소 검색 문맥이다. */
public record SearchConceptRequest(String text, SearchConceptPurpose purpose) {

    private static final int MAX_TEXT_LENGTH = 500;

    public SearchConceptRequest {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text must contain 1 to 500 characters");
        }
        text = text.trim().replaceAll("\\s+", " ");
        purpose = Objects.requireNonNull(purpose, "purpose must not be null");
    }
}
