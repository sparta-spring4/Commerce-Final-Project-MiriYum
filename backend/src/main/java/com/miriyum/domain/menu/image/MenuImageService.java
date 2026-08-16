package com.miriyum.domain.menu.image;

import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
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
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.image.PublicImageUploadValidator;
import com.miriyum.global.storage.image.PublicImageValidationException;
import com.miriyum.global.storage.image.ValidatedPublicImage;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** 메뉴 도메인이 메뉴별 공개 대표 이미지 한 장을 저장·교체·삭제한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class MenuImageService {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String SUCCESS_CODE = "SUCCESS";

    private final StoreService storeService;
    private final MenuRepository menuRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final ObjectProvider<FileStorageFacade> fileStorageFacadeProvider;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${miriyum.storage.s3.max-size-bytes:10485760}")
    private long maximumSizeBytes;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 15)
    public MenuImageCommandResult putMenuImage(
            long operatorAccountId,
            long storeId,
            long menuId,
            IdempotencyKey idempotencyKey,
            MultipartFile file
    ) {
        ValidatedPublicImage image = validate(file);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command(
                operatorAccountId, "MENU_IMAGE_PUT", idempotencyKey,
                "storeId=" + storeId + "|menuId=" + menuId + "|checksum=" + checksum(image.bytes())), () -> {
            requireLockedManagedMenu(operatorAccountId, storeId, menuId);
            FileStorageMetadata previous = confirmedImages(menuId).stream()
                    .findFirst()
                    .map(FileMetadata::toPublicMetadata)
                    .orElse(null);
            FileStorageMetadata stored = storePending(image, menuId);
            registerBeforeCommitConfirmation(stored.fileId());
            registerRollbackCleanup(stored.fileId());
            if (previous != null) {
                registerCommittedReplacementCleanup(previous.fileId());
            }
            MenuPublicImageResponse response = MenuPublicImageResponse.from(stored);
            return success(HttpStatus.OK, stored.fileId().toString(), response);
        });
        if (outcome.replayed()) {
            retryDeletedImageCleanup(menuId);
        }
        return result(outcome);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 15)
    public void deleteMenuImage(
            long operatorAccountId,
            long storeId,
            long menuId,
            IdempotencyKey idempotencyKey
    ) {
        idempotencyExecutor.execute(command(operatorAccountId, "MENU_IMAGE_DELETE", idempotencyKey,
                "storeId=" + storeId + "|menuId=" + menuId), () -> {
            requireLockedManagedMenu(operatorAccountId, storeId, menuId);
            confirmedImages(menuId).stream().findFirst().ifPresent(this::delete);
            return success(HttpStatus.NO_CONTENT, null, null);
        });
    }

    private void requireLockedManagedMenu(long operatorAccountId, long storeId, long menuId) {
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        storeService.requireMenuMutationAuthority(operatorAccountId, storeId);
        Menu menu = menuRepository.findByIdForUpdate(menuId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.MENU_NOT_FOUND));
        if (menu.getStoreId() != storeId) {
            throw new ServiceException(StoreErrorCode.MENU_NOT_FOUND);
        }
    }

    private List<FileMetadata> confirmedImages(long menuId) {
        return images(menuId, FileStorageStatus.CONFIRMED);
    }

    private List<FileMetadata> deletedImages(long menuId) {
        return images(menuId, FileStorageStatus.DELETED);
    }

    private List<FileMetadata> images(long menuId, FileStorageStatus status) {
        return fileMetadataRepository
                .findAllByOwnerTypeAndOwnerIdAndPurposeAndVisibilityAndStorageStatusOrderByCreatedAtAsc(
                        "MENU", menuId, FileStoragePurpose.MENU_IMAGE,
                        FileStorageVisibility.PUBLIC, status);
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

    private FileStorageMetadata storePending(ValidatedPublicImage image, long menuId) {
        FileStorageFacade facade = requireFacade();
        Instant now = clock.instant();
        String objectKey = "public/menus/" + menuId + "/images/" + UUID.randomUUID() + "." + image.extension();
        FileStorageMetadata metadata = new FileStorageMetadata(
                UUID.randomUUID(), new FileStorageOwner("MENU", menuId), FileStoragePurpose.MENU_IMAGE,
                objectKey, image.contentType(), image.bytes().length, checksum(image.bytes()),
                FileStorageVisibility.PUBLIC, FileStorageStatus.PENDING, "MENU_IMAGE_PUBLIC", now, null);
        try (ByteArrayInputStream content = new ByteArrayInputStream(image.bytes())) {
            return facade.storePending(metadata, new FileStorageRequest(
                    objectKey, image.contentType(), image.bytes().length, content));
        } catch (IOException exception) {
            throw new IllegalStateException("메모리 입력 스트림을 닫지 못했습니다.", exception);
        } catch (RuntimeException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private void registerBeforeCommitConfirmation(UUID imageId) {
        requireSynchronization();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                try {
                    requireFacade().confirmWithinCurrentTransaction(imageId);
                } catch (RuntimeException exception) {
                    throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
                }
            }
        });
    }

    private void registerRollbackCleanup(UUID imageId) {
        requireSynchronization();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        requireFacade().discardPending(imageId, clock.instant());
                    } catch (RuntimeException exception) {
                        log.warn("event=menu_image_rollback_cleanup_failed image_id={}", imageId);
                    }
                }
            }
        });
    }

    private void registerCommittedReplacementCleanup(UUID imageId) {
        requireSynchronization();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    delete(imageId);
                } catch (ServiceException exception) {
                    log.warn("event=menu_image_replacement_cleanup_failed image_id={}", imageId);
                }
            }
        });
    }

    private void delete(FileMetadata metadata) {
        delete(UUID.fromString(metadata.getFileId()));
    }

    private void delete(UUID imageId) {
        try {
            requireFacade().delete(imageId, clock.instant());
        } catch (RuntimeException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** 멱등 재시도에서 이전 교체의 DELETED 메타데이터도 다시 정리한다. */
    private void retryDeletedImageCleanup(long menuId) {
        deletedImages(menuId).forEach(metadata -> {
            try {
                delete(metadata);
            } catch (ServiceException exception) {
                log.warn("event=menu_image_replacement_cleanup_retry_failed image_id={}", metadata.getFileId());
            }
        });
    }

    private FileStorageFacade requireFacade() {
        FileStorageFacade facade = fileStorageFacadeProvider.getIfAvailable();
        if (facade == null) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return facade;
    }

    private IdempotencyCommand command(long accountId, String commandType, IdempotencyKey key, String input) {
        return new IdempotencyCommand(PRINCIPAL_NAMESPACE, accountId, commandType,
                key.value(), RequestFingerprint.of(input));
    }

    private MenuImageCommandResult result(IdempotentOutcome outcome) {
        if (outcome.data() == null) {
            return new MenuImageCommandResult(outcome.httpStatus(), null);
        }
        return new MenuImageCommandResult(outcome.httpStatus(),
                objectMapper.treeToValue(outcome.data(), MenuPublicImageResponse.class));
    }

    private static BusinessResult<MenuPublicImageResponse> success(
            HttpStatus status, String resourceId, MenuPublicImageResponse response
    ) {
        return new BusinessResult<>(status.value(), SUCCESS_CODE, "MENU_IMAGE", resourceId, response);
    }

    private static void requireSynchronization() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("메뉴 이미지 변경은 활성 트랜잭션 안에서 실행해야 합니다.");
        }
    }

    private static String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
