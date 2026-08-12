package com.miriyum.global.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileStorageMetadataTest {

    @Test
    @DisplayName("파일 원문이 아닌 저장 메타데이터를 보관한다")
    void storesFileMetadataWithoutRawContent() {
        UUID fileId = UUID.randomUUID();
        FileStorageOwner owner = new FileStorageOwner("STORE", 10L);
        Instant createdAt = Instant.parse("2026-08-09T00:00:00Z");

        FileStorageMetadata metadata = new FileStorageMetadata(
                fileId,
                owner,
                FileStoragePurpose.STORE_IMAGE,
                "public/store/10/store-image/test-file",
                "image/jpeg",
                5L,
                "a".repeat(64),
                FileStorageVisibility.PUBLIC,
                FileStorageStatus.CONFIRMED,
                "STORE_DEFAULT",
                createdAt,
                null
        );

        assertEquals(fileId, metadata.fileId());
        assertEquals(owner, metadata.owner());
        assertEquals(FileStoragePurpose.STORE_IMAGE, metadata.purpose());
        assertEquals(5L, metadata.sizeBytes());
        assertEquals("a".repeat(64), metadata.checksum());
        assertEquals(FileStorageStatus.CONFIRMED, metadata.status());
        assertEquals(createdAt, metadata.createdAt());
    }

    @Test
    @DisplayName("SHA-256 체크섬이 64자리가 아니면 파일 메타데이터 생성을 거절한다")
    void rejectsChecksumWithInvalidLength() {
        assertThatIllegalArgumentException().isThrownBy(() -> createMetadata(
                "short-checksum", FileStorageStatus.CONFIRMED, null));
    }

    @Test
    @DisplayName("16진수가 아닌 64자리 값은 SHA-256 체크섬으로 허용하지 않는다")
    void rejectsNonHexChecksum() {
        assertThatIllegalArgumentException().isThrownBy(() -> createMetadata(
                "z".repeat(64), FileStorageStatus.CONFIRMED, null));
    }

    @Test
    @DisplayName("소유자 식별자가 0 이하이면 파일 메타데이터를 만들지 않는다")
    void rejectsNonPositiveOwnerId() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FileStorageOwner("STORE", 0L));
    }

    @Test
    @DisplayName("제한 길이를 넘는 저장 경로는 파일 메타데이터로 만들지 않는다")
    void rejectsObjectKeyLongerThanDatabaseColumn() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FileStorageMetadata(
                UUID.randomUUID(),
                new FileStorageOwner("STORE", 10L),
                FileStoragePurpose.STORE_IMAGE,
                "a".repeat(513),
                "image/jpeg",
                5L,
                "a".repeat(64),
                FileStorageVisibility.PUBLIC,
                FileStorageStatus.PENDING,
                "STORE_DEFAULT",
                Instant.parse("2026-08-09T00:00:00Z"),
                null));
    }

    @Test
    @DisplayName("사업자등록증은 공개 메타데이터로 만들지 않는다")
    void rejectsPublicBusinessLicense() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FileStorageMetadata(
                UUID.randomUUID(),
                new FileStorageOwner("STORE_OPERATOR", 10L),
                FileStoragePurpose.BUSINESS_LICENSE,
                "private/store-operator/10/business-license/test-file",
                "image/jpeg",
                5L,
                "a".repeat(64),
                FileStorageVisibility.PUBLIC,
                FileStorageStatus.PENDING,
                "BUSINESS_LICENSE_DEFAULT",
                Instant.parse("2026-08-09T00:00:00Z"),
                null));
    }

    @Test
    @DisplayName("삭제 상태와 삭제 시각은 함께 기록해야 한다")
    void requiresDeletedAtOnlyForDeletedStatus() {
        assertThatIllegalArgumentException().isThrownBy(() -> createMetadata(
                "b".repeat(64), FileStorageStatus.DELETED, null));
        assertThatIllegalArgumentException().isThrownBy(() -> createMetadata(
                "b".repeat(64), FileStorageStatus.CONFIRMED, Instant.parse("2026-08-10T01:00:00Z")));
    }

    private FileStorageMetadata createMetadata(
            String checksum, FileStorageStatus status, Instant deletedAt) {
        return new FileStorageMetadata(
                UUID.randomUUID(),
                new FileStorageOwner("STORE", 10L),
                FileStoragePurpose.STORE_IMAGE,
                "public/store/10/store-image/test-file",
                "image/jpeg",
                5L,
                checksum,
                FileStorageVisibility.PUBLIC,
                status,
                "STORE_DEFAULT",
                Instant.parse("2026-08-09T00:00:00Z"),
                deletedAt);
    }
}
