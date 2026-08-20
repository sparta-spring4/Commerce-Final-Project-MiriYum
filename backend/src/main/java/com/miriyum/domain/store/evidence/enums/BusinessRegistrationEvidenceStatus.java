package com.miriyum.domain.store.evidence.enums;

/** 입점 신청에 연결된 사업자등록증 증빙의 공개 가능 상태다. */
public enum BusinessRegistrationEvidenceStatus {
    /** 현재 신청 version에서 심사 대상인 증빙이다. */
    CURRENT,

    /** 새 증빙으로 교체돼 접근이 즉시 차단됐으며 보존 기간만 남은 증빙이다. */
    REPLACED
}
