package com.miriyum.global.storage;

/**
 * 파일을 소유한 업무 주체를 표현한다.
 *
 * <p>공통 저장소가 특정 도메인의 계정·매장 타입에 직접 의존하지 않도록
 * 주체 종류와 식별자를 함께 보관한다.</p>
 */
public record FileStorageOwner(String type, long id) {

    public FileStorageOwner {
        // 소유자 종류
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("owner type must not be blank");
        }
        // 소유자 식별자
        if (id <= 0) {
            throw new IllegalArgumentException("owner id must be positive");
        }
    }
}
