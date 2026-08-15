package com.miriyum.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.miriyum.global.storage.entity.FileMetadata;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileMetadataTest {

    @Test
    @DisplayName("새 파일 메타데이터는 항상 대기 상태로 생성한다")
    void createsPendingMetadata() {
        // when
        FileMetadata metadata = FileMetadata.createPending(
                "1fa8bba3-4d34-465d-a6b3-b00877a86f1b",
                "STORE",
                11L,
                FileStoragePurpose.STORE_IMAGE,
                "public/store/11/store-image/object-pending",
                "image/jpeg",
                512L,
                "a".repeat(64),
                FileStorageVisibility.PUBLIC,
                "STORE_IMAGE_DEFAULT",
                Instant.parse("2026-08-10T07:30:00Z"));

        // then
        assertThat(metadata.getStorageStatus()).isEqualTo(FileStorageStatus.PENDING);
    }

    @Test
    @DisplayName("64자리가 아닌 체크섬으로 파일 메타데이터를 생성하지 않는다")
    void rejectsChecksumWithInvalidLength() {
        assertThatIllegalArgumentException().isThrownBy(() -> pendingMetadata("short-checksum"));
    }

    @Test
    @DisplayName("사업자등록증은 공개 파일로 생성하지 않는다")
    void rejectsPublicBusinessLicense() {
        assertThatIllegalArgumentException().isThrownBy(() -> FileMetadata.createPending(
                "2567edb4-91f8-4ea4-9618-8f7754fd1440",
                "STORE_OPERATOR",
                11L,
                FileStoragePurpose.BUSINESS_LICENSE,
                "private/store-operator/11/business-license/object-1",
                "image/jpeg",
                512L,
                "a".repeat(64),
                FileStorageVisibility.PUBLIC,
                "BUSINESS_LICENSE_DEFAULT",
                Instant.parse("2026-08-10T04:30:00Z")));
    }

    @Test
    @DisplayName("내부 영속 모델도 잘못된 소유자와 저장 경로를 생성 전에 거절한다")
    void rejectsInvalidRequiredFieldsBeforePersistence() {
        assertThatIllegalArgumentException().isThrownBy(() -> FileMetadata.createPending(
                "c686bf5e-7965-4caf-b8a3-17964f89dc2e",
                "STORE",
                0L,
                FileStoragePurpose.STORE_IMAGE,
                "a".repeat(513),
                "image/jpeg",
                -1L,
                "a".repeat(64),
                FileStorageVisibility.PUBLIC,
                "STORE_IMAGE_DEFAULT",
                Instant.parse("2026-08-10T04:30:00Z")));
    }

    @Test
    @DisplayName("대기 중인 파일 메타데이터를 저장 완료 상태로 변경한다")
    void confirmsPendingMetadata() {
        // given
        FileMetadata metadata = pendingMetadata();

        // when
        metadata.confirm();

        // then
        assertThat(metadata.getStorageStatus()).isEqualTo(FileStorageStatus.CONFIRMED);
    }

    @Test
    @DisplayName("저장 완료된 파일 메타데이터를 실패 상태로 다시 바꾸지 않는다")
    void rejectsFailureAfterConfirmation() {
        // given
        FileMetadata metadata = pendingMetadata();
        metadata.confirm();

        // when & then
        assertThatIllegalStateException().isThrownBy(metadata::fail);
    }

    @Test
    @DisplayName("저장 실패한 파일 메타데이터를 완료 상태로 다시 바꾸지 않는다")
    void rejectsConfirmationAfterFailure() {
        // given
        FileMetadata metadata = pendingMetadata();
        metadata.fail();

        // when & then
        assertThatIllegalStateException().isThrownBy(metadata::confirm);
    }

    @Test
    @DisplayName("저장 완료된 파일 메타데이터를 삭제 시각과 함께 삭제 상태로 전환한다")
    void deletesConfirmedMetadata() {
        // given
        FileMetadata metadata = pendingMetadata();
        metadata.confirm();
        Instant deletedAt = Instant.parse("2026-08-15T00:00:00Z");

        // when
        metadata.delete(deletedAt);

        // then
        assertThat(metadata.getStorageStatus()).isEqualTo(FileStorageStatus.DELETED);
        assertThat(metadata.getDeletedAt()).isEqualTo(deletedAt);
    }

    @Test
    void rejectsPrivateStoreImageMetadata() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FileStorageMetadata(
                UUID.randomUUID(),
                new FileStorageOwner("STORE", 7L),
                FileStoragePurpose.STORE_IMAGE,
                "private/stores/7/image.png",
                "image/png",
                10L,
                "0".repeat(64),
                FileStorageVisibility.PRIVATE,
                FileStorageStatus.PENDING,
                "STORE_IMAGE_PUBLIC",
                Instant.parse("2026-08-15T00:00:00Z"),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .withMessage("매장·메뉴 이미지는 공개 파일로만 저장할 수 있습니다.");
    }

    private FileMetadata pendingMetadata() {
        return pendingMetadata("d".repeat(64));
    }

    private FileMetadata pendingMetadata(String checksum) {
        return FileMetadata.createPending(
                "db70d80c-f0ad-4dbe-a324-f99119242342",
                "STORE",
                11L,
                FileStoragePurpose.STORE_IMAGE,
                "public/store/11/store-image/object-4",
                "image/jpeg",
                512L,
                checksum,
                FileStorageVisibility.PUBLIC,
                "STORE_IMAGE_DEFAULT",
                Instant.parse("2026-08-10T04:30:00Z"));
    }
}
