package com.miriyum.global.storage.service;

/**
 * 파일 메타데이터를 논리 삭제하기 전에 도메인별 보존 참조를 확인하는 확장 지점이다.
 *
 * <p>공통 저장소는 특정 도메인의 Entity나 Repository를 직접 참조하지 않고, 해당 도메인이 현재 참조 중인
 * 파일만 삭제를 막는다.</p>
 */
public interface FileMetadataDeletionGuard {

    boolean blocksDeletion(String fileId);
}
