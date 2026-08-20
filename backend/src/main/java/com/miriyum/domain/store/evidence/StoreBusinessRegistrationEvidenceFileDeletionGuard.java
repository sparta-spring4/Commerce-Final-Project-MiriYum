package com.miriyum.domain.store.evidence;

import com.miriyum.domain.store.evidence.repository.BusinessRegistrationEvidenceRepository;
import com.miriyum.global.storage.service.FileMetadataDeletionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 현재 사업자등록증 증빙이 참조하는 파일의 논리 삭제를 차단한다. */
@Component
@RequiredArgsConstructor
public class StoreBusinessRegistrationEvidenceFileDeletionGuard implements FileMetadataDeletionGuard {

    private static final int CURRENT_MARKER = 1;

    private final BusinessRegistrationEvidenceRepository evidenceRepository;

    @Override
    public boolean blocksDeletion(String fileId) {
        return evidenceRepository.existsByFileIdAndCurrentMarker(fileId, CURRENT_MARKER);
    }
}
