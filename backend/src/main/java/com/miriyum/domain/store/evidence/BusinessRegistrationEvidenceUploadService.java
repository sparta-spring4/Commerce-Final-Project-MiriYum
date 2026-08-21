package com.miriyum.domain.store.evidence;

import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStorageOwner;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class BusinessRegistrationEvidenceUploadService {

    private static final String OWNER_TYPE = "STORE_ONBOARDING_APPLICATION";
    private static final String RETENTION_POLICY = "STORE_ONBOARDING_PRIVATE";

    private final BusinessRegistrationEvidenceUploadValidator validator;
    private final FileStorageFacade fileStorageFacade;
    private final Clock clock;

    public PendingEvidence storePending(
            long applicationId,
            long applicationVersion,
            MultipartFile upload
    ) {
        if (applicationId <= 0 || applicationVersion <= 0) {
            throw new IllegalArgumentException("application id and version must be positive");
        }
        return storePending(applicationId, applicationVersion, validator.validate(upload));
    }

    public ValidatedBusinessRegistrationEvidence validate(MultipartFile upload) {
        return validator.validate(upload);
    }

    public PendingEvidence storePending(
            long applicationId,
            long applicationVersion,
            ValidatedBusinessRegistrationEvidence validated
    ) {
        if (applicationId <= 0 || applicationVersion <= 0 || validated == null) {
            throw new IllegalArgumentException("application id, version, and evidence are required");
        }
        byte[] bytes = validated.bytes();
        String objectKey = "private/store-onboarding/" + applicationId + "/versions/"
                + applicationVersion + "/" + validated.sha256();
        UUID fileId = stableFileId(applicationId, applicationVersion, validated.sha256());
        FileStorageMetadata metadata = new FileStorageMetadata(
                fileId,
                new FileStorageOwner(OWNER_TYPE, applicationId),
                FileStoragePurpose.BUSINESS_LICENSE,
                objectKey,
                validated.contentType(),
                bytes.length,
                validated.sha256(),
                FileStorageVisibility.PRIVATE,
                FileStorageStatus.PENDING,
                RETENTION_POLICY,
                clock.instant(),
                null);
        FileStorageMetadata stored = fileStorageFacade.storePending(
                metadata,
                new FileStorageRequest(
                        objectKey,
                        validated.contentType(),
                        bytes.length,
                        new ByteArrayInputStream(bytes)));
        return new PendingEvidence(
                stored.fileId(), stored.checksum(), stored.contentType(), stored.sizeBytes());
    }

    private static UUID stableFileId(long applicationId, long version, String sha256) {
        String identity = applicationId + ":" + version + ":" + sha256;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    public record PendingEvidence(
            UUID fileId,
            String sha256,
            String contentType,
            long sizeBytes
    ) {
    }
}
