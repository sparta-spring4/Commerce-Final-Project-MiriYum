package com.miriyum.domain.menu.image;

import com.miriyum.global.storage.FileStorageMetadata;

/** 메뉴 이미지 계약은 내부 파일 식별자 대신 공개 URL만 반환한다. */
public record MenuPublicImageResponse(String url) {

    public static MenuPublicImageResponse from(FileStorageMetadata metadata) {
        return new MenuPublicImageResponse("/api/v1/public-files/" + metadata.fileId());
    }
}
