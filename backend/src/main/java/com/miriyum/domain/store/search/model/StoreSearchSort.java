package com.miriyum.domain.store.search.model;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.Arrays;

/**
 * 외부 정렬 문자열을 고정 SQL 절로 제한한다.
 */
public enum StoreSearchSort {
    NAME_ASC("name,asc", "s.name ASC, s.store_id ASC", """
            (:cursorId IS NULL OR s.name > :cursorName
                OR (s.name = :cursorName AND s.store_id > :cursorId))
            """),
    NAME_DESC("name,desc", "s.name DESC, s.store_id ASC", """
            (:cursorId IS NULL OR s.name < :cursorName
                OR (s.name = :cursorName AND s.store_id > :cursorId))
            """),
    CREATED_AT_DESC("createdAt,desc", "s.created_at DESC, s.store_id ASC", """
            (:cursorId IS NULL OR s.created_at < :cursorCreatedAt
                OR (s.created_at = :cursorCreatedAt AND s.store_id > :cursorId))
            """),
    CREATED_AT_ASC("createdAt,asc", "s.created_at ASC, s.store_id ASC", """
            (:cursorId IS NULL OR s.created_at > :cursorCreatedAt
                OR (s.created_at = :cursorCreatedAt AND s.store_id > :cursorId))
            """);

    private final String externalValue;
    private final String orderByClause;
    private final String cursorPredicate;

    StoreSearchSort(String externalValue, String orderByClause, String cursorPredicate) {
        this.externalValue = externalValue;
        this.orderByClause = orderByClause;
        this.cursorPredicate = cursorPredicate;
    }

    public static StoreSearchSort parse(String value) {
        String resolved = value == null ? "name,asc" : value;
        return Arrays.stream(values())
                .filter(candidate -> candidate.externalValue.equals(resolved))
                .findFirst()
                .orElseThrow(() -> new ServiceException(CommonErrorCode.VALIDATION_FAILED));
    }

    public String orderByClause() {
        return orderByClause;
    }

    public String cursorPredicate() {
        return cursorPredicate;
    }
}
