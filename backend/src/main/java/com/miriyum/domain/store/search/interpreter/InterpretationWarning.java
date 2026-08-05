package com.miriyum.domain.store.search.interpreter;

import java.util.Objects;

/**
 * 민감한 원문을 복제하지 않는 검색 해석 warning이다.
 *
 * @param code warning 원인
 * @param field 영향을 받은 허용 조건 종류
 */
public record InterpretationWarning(WarningCode code, WarningField field) {

    public InterpretationWarning {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(field, "field must not be null");
    }
}
