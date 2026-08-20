package com.miriyum.domain.store.evidence;

import com.miriyum.domain.store.evidence.dto.BusinessRegistrationEvidenceCommand;
import com.miriyum.domain.store.evidence.dto.BusinessRegistrationEvidenceView;
import com.miriyum.domain.store.evidence.entity.BusinessRegistrationEvidence;
import com.miriyum.domain.store.evidence.repository.BusinessRegistrationEvidenceRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * #277이 File·Store·Auth repository를 직접 참조하지 않고 비공개 증빙 version을 다루는 Store 공개 경계다.
 *
 * <p>이 서비스는 파일 원문을 읽거나 URL을 반환하지 않는다. 실제 private S3 업로드와 심사자 일회 열람은
 * runtime 활성화 PR에서 이 원장 위에 추가한다.</p>
 */
@Service
@RequiredArgsConstructor
public class StoreBusinessRegistrationEvidenceService {

    private static final String OWNER_TYPE = "STORE_ONBOARDING_APPLICATION";
    private static final long REPLACED_RETENTION_DAYS = 7;

    private final BusinessRegistrationEvidenceRepository evidenceRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final StoreOnboardingApplicationOwnershipPort ownershipPort;
    private final Clock clock;

    /** 같은 신청 version의 현재 증빙을 교체하고 구버전은 즉시 접근 불가 상태로 전환한다. */
    @Transactional
    public BusinessRegistrationEvidenceView replaceCurrentEvidence(BusinessRegistrationEvidenceCommand command) {
        ownershipPort.requireOwnership(
                command.onboardingApplicationId(), command.applicationVersion(), command.storeOperatorAccountId());
        requirePrivateConfirmedBusinessLicense(command);
        Instant now = clock.instant();
        Optional<BusinessRegistrationEvidence> previous = evidenceRepository.findCurrentForUpdate(
                command.onboardingApplicationId(), command.applicationVersion());
        previous.ifPresent(evidence -> evidence.replace(now, now.plus(REPLACED_RETENTION_DAYS, ChronoUnit.DAYS)));

        try {
            BusinessRegistrationEvidence saved = evidenceRepository.saveAndFlush(BusinessRegistrationEvidence.createCurrent(
                    UUID.randomUUID(),
                    command.onboardingApplicationId(),
                    command.applicationVersion(),
                    command.storeOperatorAccountId(),
                    command.fileId(),
                    now));
            return BusinessRegistrationEvidenceView.from(saved);
        } catch (DataIntegrityViolationException exception) {
            // 현재 증빙이 없던 두 요청이 동시에 들어온 경우 unique current key가 하나만 승자를 남긴다.
            throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    @Transactional(readOnly = true)
    public Optional<BusinessRegistrationEvidenceView> findCurrentEvidence(
            long onboardingApplicationId,
            long applicationVersion
    ) {
        return evidenceRepository.findByOnboardingApplicationIdAndApplicationVersionAndCurrentMarker(
                        onboardingApplicationId, applicationVersion, 1)
                .map(BusinessRegistrationEvidenceView::from);
    }

    private void requirePrivateConfirmedBusinessLicense(BusinessRegistrationEvidenceCommand command) {
        // 파일 삭제 전이와 증빙 연결은 같은 행 잠금으로 직렬화한다.
        FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(command.fileId().toString())
                .orElseThrow(() -> new ServiceException(CommonErrorCode.VALIDATION_FAILED));
        if (!OWNER_TYPE.equals(metadata.getOwnerType())
                || metadata.getOwnerId() != command.onboardingApplicationId()
                || metadata.getPurpose() != FileStoragePurpose.BUSINESS_LICENSE
                || metadata.getVisibility() != FileStorageVisibility.PRIVATE
                || metadata.getStorageStatus() != FileStorageStatus.CONFIRMED) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}
