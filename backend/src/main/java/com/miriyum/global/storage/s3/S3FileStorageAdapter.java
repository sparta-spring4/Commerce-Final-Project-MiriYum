package com.miriyum.global.storage.s3;

import com.miriyum.global.storage.FileStorageObject;
import com.miriyum.global.storage.FileStoragePort;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageSaveResult;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3FileStorageAdapter implements FileStoragePort {

    private final S3Client s3Client;
    private final String bucket;

    public S3FileStorageAdapter(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public FileStorageSaveResult save(FileStorageRequest request) {
        byte[] bytes = readAllBytes(request.content());
        if (bytes.length != request.sizeBytes()) {
            throw new IllegalArgumentException("file size does not match content");
        }

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(request.objectKey())
                .contentType(request.contentType())
                .contentLength(request.sizeBytes())
                .build();
        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(bytes));

        return new FileStorageSaveResult(
                request.objectKey(),
                request.contentType(),
                request.sizeBytes(),
                sha256(bytes)
        );
    }

    @Override
    public FileStorageObject read(String objectKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(request);
        return new FileStorageObject(
                objectKey,
                response.response().contentType(),
                response.asByteArray()
        );
    }

    @Override
    public void delete(String objectKey) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        s3Client.deleteObject(request);
    }

    private byte[] readAllBytes(InputStream inputStream) {
        try (inputStream) {
            return inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read file content", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }
}
