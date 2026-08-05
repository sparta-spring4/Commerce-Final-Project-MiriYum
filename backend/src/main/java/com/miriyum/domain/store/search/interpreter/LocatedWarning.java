package com.miriyum.domain.store.search.interpreter;

import java.util.Objects;

record LocatedWarning(InterpretationWarning warning, int sourceStart) {

    LocatedWarning {
        warning = Objects.requireNonNull(warning, "warning must not be null");
        if (sourceStart < 0) {
            throw new IllegalArgumentException("sourceStart must not be negative");
        }
    }
}
