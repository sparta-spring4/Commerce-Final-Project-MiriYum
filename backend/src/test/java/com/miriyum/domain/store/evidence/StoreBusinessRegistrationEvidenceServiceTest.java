package com.miriyum.domain.store.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.store.evidence.dto.BusinessRegistrationEvidenceCommand;
import com.miriyum.domain.store.evidence.entity.BusinessRegistrationEvidence;
import com.miriyum.domain.store.evidence.enums.BusinessRegistrationEvidenceStatus;
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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class StoreBusinessRegistrationEvidenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Mock
    private BusinessRegistrationEvidenceRepository evidenceRepository;

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @Mock
    private StoreOnboardingApplicationOwnershipPort ownershipPort;

    private StoreBusinessRegistrationEvidenceService service;

    @BeforeEach
    void setUp() {
        service = new StoreBusinessRegistrationEvidenceService(
                evidenceRepository,
                fileMetadataRepository,
                ownershipPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void replacesCurrentEvidenceAndReturnsOpaqueProjection() {
        UUID previousFileId = UUID.randomUUID();
        UUID nextFileId = UUID.randomUUID();
        BusinessRegistrationEvidence previous = BusinessRegistrationEvidence.createCurrent(
                UUID.randomUUID(), 10L, 2L, 7L, previousFileId, NOW.minusSeconds(60));
        given(fileMetadataRepository.findByFileIdForUpdate(nextFileId.toString()))
                .willReturn(Optional.of(privateConfirmedLicense(nextFileId, 10L)));
        given(evidenceRepository.findCurrentForUpdate(10L, 2L)).willReturn(Optional.of(previous));
        given(evidenceRepository.saveAndFlush(any(BusinessRegistrationEvidence.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        var result = service.replaceCurrentEvidence(new BusinessRegistrationEvidenceCommand(10L, 2L, 7L, nextFileId));

        assertThat(result.evidenceId()).isNotEqualTo(nextFileId);
        assertThat(result.applicationVersion()).isEqualTo(2L);
        assertThat(result.status()).isEqualTo(BusinessRegistrationEvidenceStatus.CURRENT);
        assertThat(result.current()).isTrue();
        assertThat(previous.isCurrentEvidence()).isFalse();
        assertThat(previous.getRetentionDueAt()).isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60));

        ArgumentCaptor<BusinessRegistrationEvidence> saved = ArgumentCaptor.forClass(BusinessRegistrationEvidence.class);
        then(evidenceRepository).should().saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFileId()).isEqualTo(nextFileId.toString());
    }

    @Test
    void rejectsFileThatIsNotPrivateConfirmedBusinessLicense() {
        UUID fileId = UUID.randomUUID();
        FileMetadata publicImage = FileMetadata.createPending(
                fileId.toString(), "STORE_ONBOARDING_APPLICATION", 10L, FileStoragePurpose.STORE_IMAGE,
                "public/invalid.png", "image/png", 10L, "0".repeat(64),
                FileStorageVisibility.PUBLIC, "STORE_IMAGE_PUBLIC", NOW);
        publicImage.confirm();
        given(fileMetadataRepository.findByFileIdForUpdate(fileId.toString())).willReturn(Optional.of(publicImage));

        assertThatThrownBy(() -> service.replaceCurrentEvidence(
                new BusinessRegistrationEvidenceCommand(10L, 2L, 7L, fileId)))
                .isInstanceOf(ServiceException.class);
        then(evidenceRepository).shouldHaveNoInteractions();
    }

    @Test
    void translatesConcurrentCurrentEvidenceCreationToServiceError() {
        UUID fileId = UUID.randomUUID();
        given(fileMetadataRepository.findByFileIdForUpdate(fileId.toString()))
                .willReturn(Optional.of(privateConfirmedLicense(fileId, 10L)));
        given(evidenceRepository.findCurrentForUpdate(10L, 2L)).willReturn(Optional.empty());
        given(evidenceRepository.saveAndFlush(any(BusinessRegistrationEvidence.class)))
                .willThrow(new DataIntegrityViolationException("duplicate current evidence"));

        assertThatThrownBy(() -> service.replaceCurrentEvidence(
                new BusinessRegistrationEvidenceCommand(10L, 2L, 7L, fileId)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsEvidenceLinkWhenOnboardingOwnershipDoesNotMatch() {
        UUID fileId = UUID.randomUUID();
        org.mockito.BDDMockito.willThrow(new ServiceException(CommonErrorCode.VALIDATION_FAILED))
                .given(ownershipPort)
                .requireOwnership(10L, 2L, 8L);

        assertThatThrownBy(() -> service.replaceCurrentEvidence(
                new BusinessRegistrationEvidenceCommand(10L, 2L, 8L, fileId)))
                .isInstanceOf(ServiceException.class);
        then(fileMetadataRepository).shouldHaveNoInteractions();
        then(evidenceRepository).shouldHaveNoInteractions();
    }

    private static FileMetadata privateConfirmedLicense(UUID fileId, long applicationId) {
        FileMetadata metadata = FileMetadata.createPending(
                fileId.toString(), "STORE_ONBOARDING_APPLICATION", applicationId,
                FileStoragePurpose.BUSINESS_LICENSE,
                "private/onboarding/" + applicationId + "/" + fileId,
                "application/pdf", 10L, "0".repeat(64),
                FileStorageVisibility.PRIVATE, "BUSINESS_LICENSE_REVIEW", NOW);
        metadata.confirm();
        return metadata;
    }
}
