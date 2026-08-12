package com.miriyum.global.storage;

/**
 * 저장 파일의 업무 목적을 구분한다.
 *
 * <p>목적을 고정된 값으로 관리하면 사업자등록증과 매장·메뉴 이미지를
 * 서로 다른 공개 범위와 보존 정책으로 처리할 수 있다.</p>
 */
public enum FileStoragePurpose {
    BUSINESS_LICENSE,  // 사업자 등록증
    STORE_IMAGE,       // 매장 이미지
    MENU_IMAGE;        // 메뉴 이미지

    /** 업무 목적에 허용된 공개 범위인지 확인한다. */
    public void validateVisibility(FileStorageVisibility visibility) {
        if (this == BUSINESS_LICENSE && visibility != FileStorageVisibility.PRIVATE) {
            throw new IllegalArgumentException("사업자등록증은 비공개 파일로만 저장할 수 있습니다.");
        }
    }
}
