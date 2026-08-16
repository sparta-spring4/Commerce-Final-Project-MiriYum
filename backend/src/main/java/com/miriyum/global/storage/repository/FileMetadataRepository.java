package com.miriyum.global.storage.repository;

import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import java.util.List;
import java.util.Collection;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 파일 메타데이터의 영속 상태를 관리한다. */
public interface FileMetadataRepository extends JpaRepository<FileMetadata, String> {

    List<FileMetadata> findAllByOwnerTypeAndOwnerIdAndPurposeAndVisibilityAndStorageStatusOrderByCreatedAtAsc(
            String ownerType,
            long ownerId,
            FileStoragePurpose purpose,
            FileStorageVisibility visibility,
            FileStorageStatus storageStatus);

    List<FileMetadata> findAllByOwnerTypeAndOwnerIdAndPurposeAndVisibilityAndStorageStatusInOrderByCreatedAtAsc(
            String ownerType,
            long ownerId,
            FileStoragePurpose purpose,
            FileStorageVisibility visibility,
            Collection<FileStorageStatus> storageStatuses);

    Optional<FileMetadata> findByFileIdAndVisibilityAndStorageStatus(
            String fileId,
            FileStorageVisibility visibility,
            FileStorageStatus storageStatus);

    /** 공개·비공개 파일 조회 경로는 완료된 메타데이터만 이 메서드로 조회한다. */
    Optional<FileMetadata> findByFileIdAndStorageStatus(String fileId, FileStorageStatus storageStatus);

    /** 삭제 전이를 직렬화해 동시 요청도 하나의 정본 object key만 사용하게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT metadata FROM FileMetadata metadata WHERE metadata.fileId = :fileId")
    Optional<FileMetadata> findByFileIdForUpdate(@Param("fileId") String fileId);
}
