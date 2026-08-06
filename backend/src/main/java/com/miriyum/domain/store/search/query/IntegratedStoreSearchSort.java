package com.miriyum.domain.store.search.query;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.Arrays;

/** QueryDSL 검색에서 허용하는 안정 정렬 목록이다. */
public enum IntegratedStoreSearchSort {
    RELEVANCE_DESC("relevance,desc"),
    RECOMMENDATION_DESC("recommendation,desc"),
    NAME_ASC("name,asc"),
    NAME_DESC("name,desc"),
    CREATED_AT_ASC("createdAt,asc"),
    CREATED_AT_DESC("createdAt,desc");

    private final String externalValue;

    IntegratedStoreSearchSort(String externalValue) {
        this.externalValue = externalValue;
    }

    public String externalValue() {
        return externalValue;
    }

    static IntegratedStoreSearchSort parse(String value) {
        String resolved = value == null ? RELEVANCE_DESC.externalValue : value;
        return Arrays.stream(values())
                .filter(candidate -> candidate.externalValue.equals(resolved))
                .findFirst()
                .orElseThrow(IntegratedStoreSearchSort::validationFailed);
    }

    private static ServiceException validationFailed() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
