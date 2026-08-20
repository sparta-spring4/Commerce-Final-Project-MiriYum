package com.miriyum.global.storage;

/**
 * 파일 객체와 DB 메타데이터의 처리 상태를 나타낸다.
 */
public enum FileStorageStatus {
    /** 외부 객체 저장 또는 바깥 업무 커밋이 아직 공개 상태로 확정되지 않은 상태. */
    PENDING,

    /** 파일 저장과 메타데이터 저장이 모두 확인된 상태. */
    CONFIRMED,

    /** 파일 저장 또는 메타데이터 처리가 실패한 상태. */
    FAILED,

    /** 삭제 요청이 완료된 상태. */
    DELETED
}
