package com.miriyum.domain.store.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.dto.image.PublicImageResponse;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.storage.FileStorageObject;
import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class PublicImageServiceTest {

    @Mock
    private StoreService storeService;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private Store store;

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @Mock
    private ObjectProvider<FileStorageFacade> fileStorageFacadeProvider;

    @Mock
    private ObjectProvider<com.miriyum.global.storage.FileStoragePort> fileStoragePortProvider;

    @Mock
    private com.miriyum.global.storage.FileStoragePort fileStoragePort;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    private PublicImageService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        service = new PublicImageService(
                storeService, storeRepository, fileMetadataRepository,
                fileStorageFacadeProvider, fileStoragePortProvider, idempotencyExecutor,
                objectMapper, Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "maximumSizeBytes", 10 * 1024 * 1024L);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void listsOnlyConfirmedStoreImagesAsApplicationPublicUrls() {
        UUID imageId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        FileMetadata confirmed = FileMetadata.createPending(
                imageId.toString(), "STORE", 7L, FileStoragePurpose.STORE_IMAGE,
                "public/stores/7/images/one.png", "image/png", 8L,
                "0".repeat(64), FileStorageVisibility.PUBLIC, "STORE_IMAGE_PUBLIC",
                Instant.parse("2026-08-15T00:00:00Z"));
        confirmed.confirm();
        given(fileMetadataRepository
                .findAllByOwnerTypeAndOwnerIdAndPurposeAndVisibilityAndStorageStatusOrderByCreatedAtAsc(
                        "STORE", 7L, FileStoragePurpose.STORE_IMAGE,
                        FileStorageVisibility.PUBLIC, FileStorageStatus.CONFIRMED))
                .willReturn(List.of(confirmed));

        var images = service.listStoreImages(11L, 7L);

        assertThat(images).containsExactly(new com.miriyum.domain.store.dto.image.PublicImageResponse(
                imageId, "/api/v1/public-files/" + imageId));
    }

    @Test
    void retriesObjectDeletionForDeletedStoreImageAfterPreviousStorageFailure() {
        UUID imageId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        FileMetadata confirmed = storeImage(imageId);
        confirmed.confirm();
        FileMetadata deleted = storeImage(imageId);
        deleted.confirm();
        deleted.delete(Instant.parse("2026-08-15T00:00:00Z"));
        FileStorageFacade facade = org.mockito.Mockito.mock(FileStorageFacade.class);

        given(storeRepository.findByIdForUpdate(7L)).willReturn(Optional.of(store));
        given(fileMetadataRepository.findById(imageId.toString()))
                .willReturn(Optional.of(confirmed), Optional.of(deleted));
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(facade);
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(1);
            work.get();
            return null;
        });
        given(facade.delete(imageId, Instant.parse("2026-08-15T00:00:00Z")))
                .willThrow(new IllegalStateException("저장소 삭제 실패"))
                .willReturn(deleted.toPublicMetadata());

        assertThatThrownBy(() -> service.deleteStoreImage(
                11L, 7L, imageId, IdempotencyKey.parse("123e4567-e89b-42d3-a456-426614174000")))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class);

        service.deleteStoreImage(
                11L, 7L, imageId, IdempotencyKey.parse("223e4567-e89b-42d3-a456-426614174000"));

        then(facade).should(times(2)).delete(imageId, Instant.parse("2026-08-15T00:00:00Z"));
    }

    @Test
    void replacementReplaysNewImageAndRetriesConfirmedOldObjectCleanupWithSameIdempotencyKey() {
        UUID previousImageId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UUID replacementImageId = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
        IdempotencyKey key = IdempotencyKey.parse("323e4567-e89b-12d3-a456-426614174000");
        FileMetadata previous = storeImage(previousImageId);
        previous.confirm();
        FileMetadata replacement = storeImage(replacementImageId);
        FileMetadata confirmedReplacement = storeImage(replacementImageId);
        confirmedReplacement.confirm();
        FileStorageFacade facade = org.mockito.Mockito.mock(FileStorageFacade.class);
        AtomicReference<IdempotentOutcome> savedOutcome = new AtomicReference<>();

        given(storeRepository.findByIdForUpdate(7L)).willReturn(Optional.of(store));
        given(fileMetadataRepository.findById(previousImageId.toString()))
                .willReturn(Optional.of(previous), Optional.of(previous));
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(facade);
        given(facade.storePending(any(), any())).willReturn(replacement.toPublicMetadata());
        given(facade.confirmWithinCurrentTransaction(replacementImageId))
                .willReturn(confirmedReplacement.toPublicMetadata());
        given(facade.delete(previousImageId, Instant.parse("2026-08-15T00:00:00Z")))
                .willThrow(new IllegalStateException("저장소 삭제 실패"))
                .willReturn(previous.toPublicMetadata());
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            IdempotentOutcome replay = savedOutcome.get();
            if (replay != null) {
                return new IdempotentOutcome(true, replay.httpStatus(), replay.responseCode(),
                        replay.resourceType(), replay.resourceId(), replay.data());
            }
            @SuppressWarnings("unchecked")
            Supplier<BusinessResult<PublicImageResponse>> work = invocation.getArgument(1);
            BusinessResult<PublicImageResponse> result = work.get();
            IdempotentOutcome created = new IdempotentOutcome(false, result.httpStatus(),
                    result.responseCode(), result.resourceType(), result.resourceId(),
                    tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(result.data()));
            savedOutcome.set(created);
            return created;
        });

        TransactionSynchronizationManager.initSynchronization();
        PublicImageCommandResult first = service.replaceStoreImage(
                11L, 7L, previousImageId, key, pngFile());
        then(facade).should(never()).delete(previousImageId, Instant.parse("2026-08-15T00:00:00Z"));
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.beforeCommit(false));
        then(facade).should().confirmWithinCurrentTransaction(replacementImageId);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.clearSynchronization();
        PublicImageCommandResult replay = service.replaceStoreImage(
                11L, 7L, previousImageId, key, pngFile());

        assertThat(first).isEqualTo(replay);
        assertThat(first.data()).isEqualTo(new com.miriyum.domain.store.dto.image.PublicImageResponse(
                replacementImageId, "/api/v1/public-files/" + replacementImageId));
        then(facade).should(times(2)).delete(previousImageId,
                Instant.parse("2026-08-15T00:00:00Z"));
    }

    @Test
    void rejectsPublicImageWhenStorageContentTypeDiffersFromConfirmedMetadata() {
        UUID imageId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        byte[] bytes = pngBytes();
        FileMetadata metadata = confirmedStoreImage(imageId, bytes);
        FileStorageObject storedObject = new FileStorageObject(
                metadata.getObjectKey(), "image/jpeg", bytes);

        given(fileMetadataRepository.findByFileIdAndVisibilityAndStorageStatus(
                imageId.toString(), FileStorageVisibility.PUBLIC, FileStorageStatus.CONFIRMED))
                .willReturn(Optional.of(metadata));
        given(fileStoragePortProvider.getIfAvailable()).willReturn(fileStoragePort);
        given(fileStoragePort.read(metadata.getObjectKey())).willReturn(storedObject);

        assertThatThrownBy(() -> service.readPublicImage(imageId))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class)
                .hasMessage(CommonErrorCode.SERVICE_UNAVAILABLE.getMessage());
    }

    @Test
    void rejectsPublicImageWhenStorageSizeDiffersFromConfirmedMetadata() {
        UUID imageId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        byte[] bytes = pngBytes();
        FileMetadata metadata = confirmedStoreImage(imageId, bytes);
        FileStorageObject storedObject = new FileStorageObject(
                metadata.getObjectKey(), "image/png", new byte[] {1, 2, 3});

        given(fileMetadataRepository.findByFileIdAndVisibilityAndStorageStatus(
                imageId.toString(), FileStorageVisibility.PUBLIC, FileStorageStatus.CONFIRMED))
                .willReturn(Optional.of(metadata));
        given(fileStoragePortProvider.getIfAvailable()).willReturn(fileStoragePort);
        given(fileStoragePort.read(metadata.getObjectKey())).willReturn(storedObject);

        assertThatThrownBy(() -> service.readPublicImage(imageId))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class)
                .hasMessage(CommonErrorCode.SERVICE_UNAVAILABLE.getMessage());
    }

    @Test
    void rejectsPublicImageWhenStorageChecksumDiffersFromConfirmedMetadata() {
        UUID imageId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        byte[] bytes = pngBytes();
        FileMetadata metadata = confirmedStoreImage(imageId, bytes);
        byte[] changedBytes = pngBytes();
        changedBytes[0] = 0;
        FileStorageObject storedObject = new FileStorageObject(
                metadata.getObjectKey(), "image/png", changedBytes);

        given(fileMetadataRepository.findByFileIdAndVisibilityAndStorageStatus(
                imageId.toString(), FileStorageVisibility.PUBLIC, FileStorageStatus.CONFIRMED))
                .willReturn(Optional.of(metadata));
        given(fileStoragePortProvider.getIfAvailable()).willReturn(fileStoragePort);
        given(fileStoragePort.read(metadata.getObjectKey())).willReturn(storedObject);

        assertThatThrownBy(() -> service.readPublicImage(imageId))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class)
                .hasMessage(CommonErrorCode.SERVICE_UNAVAILABLE.getMessage());
    }

    @Test
    void keepsUploadUnpublishedWhenOuterIdempotencyTransactionRollsBackAndCompensationFails() {
        UUID uploadedImageId = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
        FileStorageMetadata uploaded = storeImage(uploadedImageId).toPublicMetadata();
        FileStorageFacade facade = org.mockito.Mockito.mock(FileStorageFacade.class);
        TransactionSynchronizationManager.initSynchronization();

        given(storeRepository.findByIdForUpdate(7L)).willReturn(Optional.of(store));
        given(fileMetadataRepository
                .findAllByOwnerTypeAndOwnerIdAndPurposeAndVisibilityAndStorageStatusOrderByCreatedAtAsc(
                        "STORE", 7L, FileStoragePurpose.STORE_IMAGE,
                        FileStorageVisibility.PUBLIC, FileStorageStatus.CONFIRMED))
                .willReturn(List.of());
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(facade);
        given(facade.storePending(any(), any())).willReturn(uploaded);
        given(facade.discardPending(uploadedImageId, Instant.parse("2026-08-15T00:00:00Z")))
                .willThrow(new IllegalStateException("보상 상태 전이 실패"));
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<BusinessResult<PublicImageResponse>> work = invocation.getArgument(1);
            work.get();
            throw new IllegalStateException("멱등 결과 저장 실패");
        });

        assertThatThrownBy(() -> service.uploadStoreImage(
                11L, 7L, IdempotencyKey.parse("123e4567-e89b-42d3-a456-426614174000"), pngFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("멱등 결과 저장 실패");

        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        then(facade).should().discardPending(uploadedImageId, Instant.parse("2026-08-15T00:00:00Z"));
        then(facade).should(never()).confirmWithinCurrentTransaction(uploadedImageId);
    }

    private FileMetadata storeImage(UUID imageId) {
        return FileMetadata.createPending(
                imageId.toString(), "STORE", 7L, FileStoragePurpose.STORE_IMAGE,
                "public/stores/7/images/one.png", "image/png", 8L,
                "0".repeat(64), FileStorageVisibility.PUBLIC, "STORE_IMAGE_PUBLIC",
                Instant.parse("2026-08-15T00:00:00Z"));
    }

    private FileMetadata confirmedStoreImage(UUID imageId, byte[] bytes) {
        FileMetadata metadata = FileMetadata.createPending(
                imageId.toString(), "STORE", 7L, FileStoragePurpose.STORE_IMAGE,
                "public/stores/7/images/" + imageId + ".png", "image/png", bytes.length,
                checksum(bytes), FileStorageVisibility.PUBLIC, "STORE_IMAGE_PUBLIC",
                Instant.parse("2026-08-15T00:00:00Z"));
        metadata.confirm();
        return metadata;
    }

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "store.png", "image/png", pngBytes());
    }

    private byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }

    private String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
