package com.miriyum.domain.store.evidence;

import com.miriyum.domain.store.evidence.repository.BusinessRegistrationEvidenceRepository;
import com.miriyum.global.storage.service.FileMetadataDeletionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 전용 파기 worker가 처리하기 전 사업자등록증 증빙 파일의 일반 논리 삭제를 차단한다. */
@Component
@RequiredArgsConstructor
public class StoreBusinessRegistrationEvidenceFileDeletionGuard implements FileMetadataDeletionGuard {

    private final BusinessRegistrationEvidenceRepository evidenceRepository;

    @Override
    public boolean blocksDeletion(String fileId) {
        return evidenceRepository.findByFileIdForUpdate(fileId).isPresent();
    }
}
