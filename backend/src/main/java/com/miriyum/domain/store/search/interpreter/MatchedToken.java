package com.miriyum.domain.store.search.interpreter;

import java.util.Objects;

record MatchedToken<T>(T value, TextSpan span) {

    MatchedToken {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(span, "span must not be null");
    }
}
