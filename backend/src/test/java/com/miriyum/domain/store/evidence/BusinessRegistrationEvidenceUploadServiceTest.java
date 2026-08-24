package com.miriyum.domain.store.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.service.FileStorageFacade;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class BusinessRegistrationEvidenceUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Mock
    private BusinessRegistrationEvidenceUploadValidator validator;

    @Mock
    private FileStorageFacade fileStorageFacade;

    @Mock
    private ObjectProvider<FileStorageFacade> fileStorageFacadeProvider;

    @Test
    void storesPendingEvidenceWithPrivateStableIdentity() {
        byte[] bytes = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        String checksum = "a".repeat(64);
        MockMultipartFile upload = new MockMultipartFile("file", "license.png", "image/png", bytes);
        given(validator.validate(upload)).willReturn(
                new ValidatedBusinessRegistrationEvidence("image/png", bytes, checksum));
        given(fileStorageFacade.storePending(any(), any()))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(fileStorageFacade);
        BusinessRegistrationEvidenceUploadService service =
                new BusinessRegistrationEvidenceUploadService(
                        validator, fileStorageFacadeProvider, Clock.fixed(NOW, ZoneOffset.UTC), true);

        var result = service.storePending(41L, 2L, upload);

        ArgumentCaptor<FileStorageMetadata> metadata =
                ArgumentCaptor.forClass(FileStorageMetadata.class);
        ArgumentCaptor<FileStorageRequest> request =
                ArgumentCaptor.forClass(FileStorageRequest.class);
        then(fileStorageFacade).should().storePending(metadata.capture(), request.capture());
        assertThat(metadata.getValue().owner().type()).isEqualTo("STORE_ONBOARDING_APPLICATION");
        assertThat(metadata.getValue().owner().id()).isEqualTo(41L);
        assertThat(metadata.getValue().visibility()).isEqualTo(FileStorageVisibility.PRIVATE);
        assertThat(metadata.getValue().status()).isEqualTo(FileStorageStatus.PENDING);
        assertThat(metadata.getValue().objectKey())
                .isEqualTo("private/store-onboarding/41/versions/2/" + checksum);
        assertThat(request.getValue().objectKey()).isEqualTo(metadata.getValue().objectKey());
        assertThat(result.fileId()).isEqualTo(metadata.getValue().fileId());
        assertThat(result.sha256()).isEqualTo(checksum);
    }

    @Test
    void rejectsEvidenceStorageWhenTheRuntimeAdapterIsUnavailable() {
        BusinessRegistrationEvidenceUploadService service =
                new BusinessRegistrationEvidenceUploadService(
                        validator, fileStorageFacadeProvider, Clock.fixed(NOW, ZoneOffset.UTC), true);
        var validated = new ValidatedBusinessRegistrationEvidence(
                "image/png", new byte[] {1}, "a".repeat(64));

        assertThatThrownBy(() -> service.storePending(41L, 2L, validated))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getErrorCode())
                        .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void rejectsPrivateEvidenceStorageWhenTheDedicatedS3GateIsDisabled() {
        BusinessRegistrationEvidenceUploadService service =
                new BusinessRegistrationEvidenceUploadService(
                        validator, fileStorageFacadeProvider, Clock.fixed(NOW, ZoneOffset.UTC), false);
        var validated = new ValidatedBusinessRegistrationEvidence(
                "image/png", new byte[] {1}, "a".repeat(64));

        assertThatThrownBy(() -> service.storePending(41L, 2L, validated))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getErrorCode())
                        .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
        then(fileStorageFacadeProvider).shouldHaveNoInteractions();
    }
}
