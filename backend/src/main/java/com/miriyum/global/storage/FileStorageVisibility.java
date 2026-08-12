package com.miriyum.global.storage;

/**
 * 저장 파일의 외부 공개 범위를 구분한다.
 */
public enum FileStorageVisibility {
    /** 사업자등록증처럼 권한을 확인한 사용자에게만 제공하는 파일. */
    PRIVATE,

    /** 매장·메뉴 이미지처럼 공개 조회를 허용하는 파일. */
    PUBLIC
}
