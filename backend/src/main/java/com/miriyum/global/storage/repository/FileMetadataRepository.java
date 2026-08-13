package com.miriyum.global.storage.repository;

import com.miriyum.global.storage.entity.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

/** 파일 메타데이터의 영속 상태를 관리한다. */
public interface FileMetadataRepository extends JpaRepository<FileMetadata, String> {
}
