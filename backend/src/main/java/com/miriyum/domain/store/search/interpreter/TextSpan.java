package com.miriyum.domain.store.search.interpreter;

record TextSpan(int startInclusive, int endExclusive) {

    TextSpan {
        if (startInclusive < 0 || endExclusive <= startInclusive) {
            throw new IllegalArgumentException("text span must be non-empty and ordered");
        }
    }

    boolean overlaps(TextSpan other) {
        return startInclusive < other.endExclusive && other.startInclusive < endExclusive;
    }

    int length() {
        return endExclusive - startInclusive;
    }
}
