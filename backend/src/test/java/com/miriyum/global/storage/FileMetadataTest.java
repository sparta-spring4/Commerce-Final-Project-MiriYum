package com.miriyum.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.miriyum.global.storage.entity.FileMetadata;
import java.time.LocalDateTime;
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
                LocalDateTime.of(2026, 8, 10, 16, 30));

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
                LocalDateTime.of(2026, 8, 10, 13, 30)));
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
                LocalDateTime.of(2026, 8, 10, 13, 30));
    }
}
