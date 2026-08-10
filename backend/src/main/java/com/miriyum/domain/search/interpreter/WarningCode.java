package com.miriyum.domain.search.interpreter;

/** 검색 입력을 추측하지 않고 보존한 이유를 나타낸다. */
public enum WarningCode {
    AMBIGUOUS_DICTIONARY_TERM,
    AMBIGUOUS_PRICE,
    CONFLICTING_PRICE,
    INVALID_PARTY_SIZE,
    CONFLICTING_PARTY_SIZE,
    AMBIGUOUS_DATE,
    CONFLICTING_DATE,
    AMBIGUOUS_TIME,
    CONFLICTING_TIME,
    OUT_OF_RANGE_NUMBER
}
