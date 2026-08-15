package com.miriyum.global.storage.repository;

import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 파일 메타데이터의 영속 상태를 관리한다. */
public interface FileMetadataRepository extends JpaRepository<FileMetadata, String> {

    List<FileMetadata> findAllByOwnerTypeAndOwnerIdAndPurposeAndVisibilityAndStorageStatusOrderByCreatedAtAsc(
            String ownerType,
            long ownerId,
            FileStoragePurpose purpose,
            FileStorageVisibility visibility,
            FileStorageStatus storageStatus);

    Optional<FileMetadata> findByFileIdAndVisibilityAndStorageStatus(
            String fileId,
            FileStorageVisibility visibility,
            FileStorageStatus storageStatus);
}
