package com.miriyum.global.storage.s3;

import com.miriyum.global.storage.FileStorageObject;
import com.miriyum.global.storage.FileStorageOutcomeUnknownException;
import com.miriyum.global.storage.FileStoragePort;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageSaveResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

@Slf4j
public class S3FileStorageAdapter implements FileStoragePort {

    private static final int BUFFER_SIZE = 8 * 1024;

    private final S3Client s3Client;
    private final String bucket;
    private final long maxSizeBytes;
    private final TemporaryFileDeleter temporaryFileDeleter;

    public S3FileStorageAdapter(S3Client s3Client, String bucket, long maxSizeBytes) {
        this(s3Client, bucket, maxSizeBytes, Files::deleteIfExists);
    }

    S3FileStorageAdapter(
            S3Client s3Client,
            String bucket,
            long maxSizeBytes,
            TemporaryFileDeleter temporaryFileDeleter
    ) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.maxSizeBytes = maxSizeBytes;
        this.temporaryFileDeleter = temporaryFileDeleter;
    }

    @Override
    public FileStorageSaveResult save(FileStorageRequest request) {
        validateSizeLimit(request.sizeBytes());
        requireVersioningDisabled();
        PreparedUpload preparedUpload = prepareUpload(request);
        RuntimeException failure = null;
        boolean uploaded = false;
        String uploadedVersionId = null;
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(request.objectKey())
                    .contentType(request.contentType())
                    .contentLength(request.sizeBytes())
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                    .checksumSHA256(preparedUpload.checksumBase64())
                    .build();
            PutObjectResponse putObjectResponse = putObject(putObjectRequest, preparedUpload.file());
            uploaded = true;
            uploadedVersionId = putObjectResponse.versionId();

            HeadObjectResponse storedObject = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(request.objectKey())
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
            verifyStoredObject(request, preparedUpload, storedObject);

            return new FileStorageSaveResult(
                    request.objectKey(),
                    storedObject.contentType(),
                    storedObject.contentLength(),
                    preparedUpload.checksumHex()
            );
        } catch (RuntimeException exception) {
            failure = exception;
            if (uploaded && !deleteUploadedObject(request.objectKey(), uploadedVersionId, exception)) {
                throw new FileStorageOutcomeUnknownException(
                        "uploaded object outcome cannot be determined safely", exception);
            }
            throw exception;
        } finally {
            deleteTemporaryFile(preparedUpload.file(), failure);
        }
    }

    @Override
    public FileStorageObject read(String objectKey) {
        HeadObjectResponse metadata = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build());
        validateSizeLimit(metadata.contentLength());
        String eTag = metadata.eTag();
        if (eTag == null || eTag.isBlank()) {
            throw new IllegalStateException("stored object ETag is missing");
        }
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .ifMatch(eTag)
                .build();
        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(request);
        validateSizeLimit(response.response().contentLength());
        byte[] bytes = response.asByteArray();
        if (response.response().contentLength() == null || response.response().contentLength() != bytes.length) {
            throw new IllegalStateException("stored file size does not match content");
        }
        return new FileStorageObject(
                objectKey,
                response.response().contentType(),
                bytes
        );
    }

    @Override
    public void delete(String objectKey) {
        requireVersioningDisabled();
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        s3Client.deleteObject(request);
    }

    private PutObjectResponse putObject(PutObjectRequest request, Path content) {
        try {
            return s3Client.putObject(request, RequestBody.fromFile(content));
        } catch (RuntimeException exception) {
            // S3 may persist the object even when the response is lost, so reconciliation must retain PENDING.
            throw new FileStorageOutcomeUnknownException("PutObject outcome cannot be determined safely", exception);
        }
    }

    private void requireVersioningDisabled() {
        GetBucketVersioningResponse response = s3Client.getBucketVersioning(GetBucketVersioningRequest.builder()
                .bucket(bucket)
                .build());
        if (response != null && response.status() != null) {
            throw new IllegalStateException("S3 bucket Versioning must be disabled for file cleanup");
        }
    }

    private PreparedUpload prepareUpload(FileStorageRequest request) {
        Path temporaryFile = createTemporaryFile();
        try (InputStream inputStream = request.content();
             OutputStream outputStream = Files.newOutputStream(temporaryFile)) {
            MessageDigest digest = messageDigest();
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalBytes = 0;
            while (true) {
                int maximumReadLength = (int) Math.min(
                        buffer.length,
                        request.sizeBytes() - totalBytes + 1
                );
                if (maximumReadLength <= 0) {
                    throw new IllegalArgumentException("file size does not match content");
                }
                int readBytes = inputStream.read(buffer, 0, maximumReadLength);
                if (readBytes < 0) {
                    break;
                }
                outputStream.write(buffer, 0, readBytes);
                digest.update(buffer, 0, readBytes);
                totalBytes += readBytes;
                if (totalBytes > request.sizeBytes()) {
                    throw new IllegalArgumentException("file size does not match content");
                }
            }
            if (totalBytes != request.sizeBytes()) {
                throw new IllegalArgumentException("file size does not match content");
            }
            byte[] checksum = digest.digest();
            return new PreparedUpload(
                    temporaryFile,
                    toLowercaseHex(checksum),
                    Base64.getEncoder().encodeToString(checksum)
            );
        } catch (IOException exception) {
            IllegalStateException failure = new IllegalStateException("failed to prepare file content", exception);
            deleteTemporaryFile(temporaryFile, failure);
            throw failure;
        } catch (RuntimeException exception) {
            deleteTemporaryFile(temporaryFile, exception);
            throw exception;
        }
    }

    private void verifyStoredObject(
            FileStorageRequest request,
            PreparedUpload preparedUpload,
            HeadObjectResponse storedObject
    ) {
        if (storedObject.contentLength() == null || storedObject.contentLength() != request.sizeBytes()) {
            throw new IllegalStateException("stored file size does not match content");
        }
        if (!request.contentType().equals(storedObject.contentType())) {
            throw new IllegalStateException("stored file content type does not match content");
        }
        if (!preparedUpload.checksumBase64().equals(storedObject.checksumSHA256())) {
            throw new IllegalStateException("stored file checksum does not match content");
        }
    }

    private void validateSizeLimit(Long sizeBytes) {
        if (sizeBytes == null || sizeBytes < 0 || sizeBytes > maxSizeBytes) {
            throw new IllegalArgumentException("file size exceeds the configured limit");
        }
    }

    private Path createTemporaryFile() {
        try {
            return Files.createTempFile("miriyum-s3-upload-", ".tmp");
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create temporary file", exception);
        }
    }

    private boolean deleteUploadedObject(String objectKey, String versionId, RuntimeException failure) {
        if (!isCompensatableVersionId(versionId)) {
            return false;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .versionId(versionId)
                    .build());
            return true;
        } catch (RuntimeException cleanupException) {
            failure.addSuppressed(cleanupException);
            return false;
        }
    }

    private boolean isCompensatableVersionId(String versionId) {
        return versionId != null && !versionId.isBlank() && !"null".equals(versionId);
    }

    private void deleteTemporaryFile(Path temporaryFile, RuntimeException failure) {
        try {
            temporaryFileDeleter.deleteIfExists(temporaryFile);
        } catch (IOException exception) {
            IllegalStateException cleanupException = new IllegalStateException("failed to delete temporary file", exception);
            if (failure != null) {
                failure.addSuppressed(cleanupException);
                return;
            }
            // A remote object was already verified. Local temp-file cleanup must not turn that success into FAILED.
            log.warn("event=file_storage_temporary_file_cleanup_failed");
        }
    }

    @FunctionalInterface
    interface TemporaryFileDeleter {

        boolean deleteIfExists(Path temporaryFile) throws IOException;
    }

    private MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }

    private String toLowercaseHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private record PreparedUpload(Path file, String checksumHex, String checksumBase64) {
    }
}
