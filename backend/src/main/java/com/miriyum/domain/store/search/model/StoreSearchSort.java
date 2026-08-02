package com.miriyum.domain.store.search.model;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.Arrays;

/**
 * 외부 정렬 문자열을 고정 SQL 절로 제한한다.
 */
public enum StoreSearchSort {
    NAME_ASC("name,asc", "s.name ASC, s.store_id ASC"),
    NAME_DESC("name,desc", "s.name DESC, s.store_id ASC"),
    CREATED_AT_DESC("createdAt,desc", "s.created_at DESC, s.store_id ASC"),
    CREATED_AT_ASC("createdAt,asc", "s.created_at ASC, s.store_id ASC");

    private final String externalValue;
    private final String orderByClause;

    StoreSearchSort(String externalValue, String orderByClause) {
        this.externalValue = externalValue;
        this.orderByClause = orderByClause;
    }

    public static StoreSearchSort parse(String value) {
        String resolved = value == null || value.isBlank() ? "name,asc" : value;
        return Arrays.stream(values())
                .filter(candidate -> candidate.externalValue.equals(resolved))
                .findFirst()
                .orElseThrow(() -> new ServiceException(CommonErrorCode.VALIDATION_FAILED));
    }

    public String orderByClause() {
        return orderByClause;
    }
}
