package com.miriyum.domain.store.dto.image;

import com.miriyum.global.storage.FileStorageMetadata;
import java.util.UUID;

/** S3 내부 정보 없이 애플리케이션 공개 이미지 경로만 노출하는 응답이다. */
public record PublicImageResponse(UUID imageId, String url) {

    public static PublicImageResponse from(FileStorageMetadata metadata) {
        return new PublicImageResponse(
                metadata.fileId(),
                "/api/v1/public-files/" + metadata.fileId());
    }
}
