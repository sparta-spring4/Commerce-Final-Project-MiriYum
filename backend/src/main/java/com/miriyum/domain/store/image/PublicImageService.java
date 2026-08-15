package com.miriyum.domain.store.image;

import com.miriyum.domain.store.dto.image.PublicImageResponse;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStorageOwner;
import com.miriyum.global.storage.FileStoragePort;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** 매장 운영자가 공개 매장 이미지를 저장하고 교체·삭제하는 업무 서비스다. */
@Service
@RequiredArgsConstructor
public class PublicImageService {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String SUCCESS_CODE = "SUCCESS";
    private static final int STORE_IMAGE_LIMIT = 10;

    private final StoreService storeService;
    private final StoreRepository storeRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final ObjectProvider<FileStorageFacade> fileStorageFacadeProvider;
    private final ObjectProvider<FileStoragePort> fileStoragePortProvider;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${miriyum.storage.s3.max-size-bytes:10485760}")
    private long maximumSizeBytes;

    @Transactional(readOnly = true)
    public List<PublicImageResponse> listStoreImages(long operatorAccountId, long storeId) {
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        return confirmedImages("STORE", storeId, FileStoragePurpose.STORE_IMAGE).stream()
                .map(FileMetadata::toPublicMetadata)
                .map(PublicImageResponse::from)
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 15)
    public PublicImageCommandResult uploadStoreImage(
            long operatorAccountId,
            long storeId,
            IdempotencyKey idempotencyKey,
            MultipartFile file
    ) {
        ValidatedPublicImage image = validate(file);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command(
                operatorAccountId,
                "STORE_IMAGE_UPLOAD",
                idempotencyKey,
                "storeId=" + storeId + "|checksum=" + checksum(image.bytes())), () -> {
            requireLockedStoreOwnership(operatorAccountId, storeId);
            if (confirmedImages("STORE", storeId, FileStoragePurpose.STORE_IMAGE).size() >= STORE_IMAGE_LIMIT) {
                throw new ServiceException(StoreErrorCode.PUBLIC_IMAGE_LIMIT_EXCEEDED);
            }
            FileStorageMetadata stored = store(image, new FileStorageOwner("STORE", storeId),
                    FileStoragePurpose.STORE_IMAGE, storeObjectKey(storeId, image));
            PublicImageResponse response = PublicImageResponse.from(stored);
            return success(HttpStatus.CREATED, "STORE_IMAGE", stored.fileId().toString(), response);
        });
        return result(outcome);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 15)
    public PublicImageCommandResult replaceStoreImage(
            long operatorAccountId,
            long storeId,
            UUID imageId,
            IdempotencyKey idempotencyKey,
            MultipartFile file
    ) {
        ValidatedPublicImage image = validate(file);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command(
                operatorAccountId,
                "STORE_IMAGE_REPLACE",
                idempotencyKey,
                "storeId=" + storeId + "|imageId=" + imageId + "|checksum=" + checksum(image.bytes())), () -> {
            requireLockedStoreOwnership(operatorAccountId, storeId);
            FileStorageMetadata previous = requireConfirmedImage(
                    imageId, "STORE", storeId, FileStoragePurpose.STORE_IMAGE);
            FileStorageMetadata stored = store(image, new FileStorageOwner("STORE", storeId),
                    FileStoragePurpose.STORE_IMAGE, storeObjectKey(storeId, image));
            deleteAfterReplacement(previous);
            PublicImageResponse response = PublicImageResponse.from(stored);
            return success(HttpStatus.OK, "STORE_IMAGE", stored.fileId().toString(), response);
        });
        return result(outcome);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 15)
    public void deleteStoreImage(
            long operatorAccountId,
            long storeId,
            UUID imageId,
            IdempotencyKey idempotencyKey
    ) {
        idempotencyExecutor.execute(command(operatorAccountId, "STORE_IMAGE_DELETE", idempotencyKey,
                "storeId=" + storeId + "|imageId=" + imageId), () -> {
            requireLockedStoreOwnership(operatorAccountId, storeId);
            findDeletableImage(imageId, "STORE", storeId, FileStoragePurpose.STORE_IMAGE)
                    .ifPresent(this::deleteAfterReplacement);
            return success(HttpStatus.NO_CONTENT, null, null, null);
        });
    }

    @Transactional(readOnly = true)
    public com.miriyum.global.storage.FileStorageObject readPublicImage(UUID imageId) {
        FileStorageMetadata metadata = fileMetadataRepository.findByFileIdAndVisibilityAndStorageStatus(
                        imageId.toString(), FileStorageVisibility.PUBLIC, FileStorageStatus.CONFIRMED)
                .map(FileMetadata::toPublicMetadata)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.PUBLIC_IMAGE_NOT_FOUND));
        FileStoragePort port = fileStoragePortProvider.getIfAvailable();
        if (port == null) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return port.read(metadata.objectKey());
        } catch (RuntimeException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private ValidatedPublicImage validate(MultipartFile file) {
        if (file == null) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        try {
            return new PublicImageUploadValidator(maximumSizeBytes)
                    .validate(file.getContentType(), file.getBytes());
        } catch (PublicImageValidationException exception) {
            if (exception.reason() == PublicImageValidationException.Reason.SIZE_EXCEEDED) {
                throw new ServiceException(StoreErrorCode.PUBLIC_IMAGE_SIZE_EXCEEDED);
            }
            if (exception.reason() == PublicImageValidationException.Reason.UNSUPPORTED_MEDIA_TYPE) {
                throw new ServiceException(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE);
            }
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        } catch (IOException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private FileStorageMetadata store(
            ValidatedPublicImage image,
            FileStorageOwner owner,
            FileStoragePurpose purpose,
            String objectKey
    ) {
        FileStorageFacade facade = fileStorageFacadeProvider.getIfAvailable();
        if (facade == null) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        Instant now = clock.instant();
        FileStorageMetadata metadata = new FileStorageMetadata(
                UUID.randomUUID(), owner, purpose, objectKey, image.contentType(), image.bytes().length,
                checksum(image.bytes()), FileStorageVisibility.PUBLIC, FileStorageStatus.PENDING,
                purpose.name() + "_PUBLIC", now, null);
        try (ByteArrayInputStream content = new ByteArrayInputStream(image.bytes())) {
            return facade.store(metadata, new FileStorageRequest(
                    objectKey, image.contentType(), image.bytes().length, content));
        } catch (IOException exception) {
            throw new IllegalStateException("메모리 입력 스트림을 닫지 못했습니다.", exception);
        } catch (RuntimeException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private void deleteAfterReplacement(FileStorageMetadata metadata) {
        deleteAfterReplacement(metadata.fileId());
    }

    private void deleteAfterReplacement(UUID imageId) {
        FileStorageFacade facade = fileStorageFacadeProvider.getIfAvailable();
        if (facade == null) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            facade.delete(imageId, clock.instant());
        } catch (RuntimeException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private void requireLockedStoreOwnership(long operatorAccountId, long storeId) {
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        store.requireManagedBy(operatorAccountId);
    }

    private List<FileMetadata> confirmedImages(String ownerType, long ownerId, FileStoragePurpose purpose) {
        return fileMetadataRepository
                .findAllByOwnerTypeAndOwnerIdAndPurposeAndVisibilityAndStorageStatusOrderByCreatedAtAsc(
                        ownerType, ownerId, purpose, FileStorageVisibility.PUBLIC, FileStorageStatus.CONFIRMED);
    }

    private java.util.Optional<FileStorageMetadata> findConfirmedImage(
            UUID imageId, String ownerType, long ownerId, FileStoragePurpose purpose
    ) {
        return fileMetadataRepository.findById(imageId.toString())
                .filter(metadata -> metadata.getOwnerType().equals(ownerType)
                        && metadata.getOwnerId() == ownerId
                        && metadata.getPurpose() == purpose
                        && metadata.getVisibility() == FileStorageVisibility.PUBLIC
                        && metadata.getStorageStatus() == FileStorageStatus.CONFIRMED)
                .map(FileMetadata::toPublicMetadata);
    }

    private java.util.Optional<FileStorageMetadata> findDeletableImage(
            UUID imageId, String ownerType, long ownerId, FileStoragePurpose purpose
    ) {
        return fileMetadataRepository.findById(imageId.toString())
                .filter(metadata -> metadata.getOwnerType().equals(ownerType)
                        && metadata.getOwnerId() == ownerId
                        && metadata.getPurpose() == purpose
                        && metadata.getVisibility() == FileStorageVisibility.PUBLIC
                        && (metadata.getStorageStatus() == FileStorageStatus.CONFIRMED
                        || metadata.getStorageStatus() == FileStorageStatus.DELETED))
                .map(FileMetadata::toPublicMetadata);
    }

    private FileStorageMetadata requireConfirmedImage(
            UUID imageId, String ownerType, long ownerId, FileStoragePurpose purpose
    ) {
        return findConfirmedImage(imageId, ownerType, ownerId, purpose)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.PUBLIC_IMAGE_NOT_FOUND));
    }

    private IdempotencyCommand command(
            long operatorAccountId,
            String commandType,
            IdempotencyKey key,
            String canonicalInput
    ) {
        return new IdempotencyCommand(PRINCIPAL_NAMESPACE, operatorAccountId, commandType,
                key.value(), RequestFingerprint.of(canonicalInput));
    }

    private PublicImageCommandResult result(IdempotentOutcome outcome) {
        if (outcome.data() == null) {
            return new PublicImageCommandResult(outcome.httpStatus(), null);
        }
        return new PublicImageCommandResult(outcome.httpStatus(),
                objectMapper.treeToValue(outcome.data(), PublicImageResponse.class));
    }

    private static BusinessResult<PublicImageResponse> success(
            HttpStatus status, String resourceType, String resourceId, PublicImageResponse response
    ) {
        return new BusinessResult<>(status.value(), SUCCESS_CODE, resourceType, resourceId, response);
    }

    private static String storeObjectKey(long storeId, ValidatedPublicImage image) {
        return "public/stores/" + storeId + "/images/" + UUID.randomUUID() + "." + image.extension();
    }

    private static String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
