package com.miriyum.domain.menu.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MenuImageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @Mock private StoreService storeService;
    @Mock private MenuRepository menuRepository;
    @Mock private Menu menu;
    @Mock private ObjectProvider<FileStorageFacade> fileStorageFacadeProvider;
    @Mock private IdempotencyExecutor idempotencyExecutor;
    @Mock private FileStorageFacade fileStorageFacade;

    private MenuImageService service;

    @BeforeEach
    void setUp() {
        service = new MenuImageService(storeService, menuRepository, fileStorageFacadeProvider,
                idempotencyExecutor, JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "maximumSizeBytes", 10 * 1024 * 1024L);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void replacesExistingConfirmedImageOnlyAfterNewImageIsConfirmed() {
        UUID previousImageId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UUID replacementImageId = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
        FileStorageMetadata previous = confirmedMenuImage(previousImageId).toPublicMetadata();
        FileStorageMetadata replacement = menuImage(replacementImageId).toPublicMetadata();
        FileStorageMetadata confirmedReplacement = confirmedMenuImage(replacementImageId).toPublicMetadata();

        given(menuRepository.findByIdForUpdate(13L)).willReturn(Optional.of(menu));
        given(menu.getStoreId()).willReturn(7L);
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(fileStorageFacade);
        given(fileStorageFacade.findPublicMetadata(any(), org.mockito.ArgumentMatchers.eq(FileStoragePurpose.MENU_IMAGE), any()))
                .willReturn(List.of(previous));
        given(fileStorageFacade.storePending(any(), any())).willReturn(replacement);
        given(fileStorageFacade.confirmWithinCurrentTransaction(replacementImageId)).willReturn(confirmedReplacement);
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<BusinessResult<MenuPublicImageResponse>> work = invocation.getArgument(1);
            BusinessResult<MenuPublicImageResponse> result = work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(), result.resourceType(),
                    result.resourceId(), JsonMapper.builder().build().valueToTree(result.data()));
        });

        TransactionSynchronizationManager.initSynchronization();
        MenuImageCommandResult result = service.putMenuImage(11L, 7L, 13L,
                IdempotencyKey.parse("323e4567-e89b-42d3-a456-426614174000"), pngFile());

        then(fileStorageFacade).should(org.mockito.Mockito.never()).delete(previousImageId, NOW);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.beforeCommit(false));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(result.data()).isEqualTo(new MenuPublicImageResponse("/api/v1/public-files/" + replacementImageId));
        then(fileStorageFacade).should().markDeletedWithinCurrentTransaction(previousImageId, NOW);
        then(fileStorageFacade).should().delete(previousImageId, NOW);
    }

    @Test
    void deleteWithoutConfirmedImageConvergesToNoContent() {
        given(menuRepository.findByIdForUpdate(13L)).willReturn(Optional.of(menu));
        given(menu.getStoreId()).willReturn(7L);
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(fileStorageFacade);
        given(fileStorageFacade.findPublicMetadata(any(), org.mockito.ArgumentMatchers.eq(FileStoragePurpose.MENU_IMAGE), any()))
                .willReturn(List.of());
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            @SuppressWarnings("unchecked") Supplier<BusinessResult<Void>> work = invocation.getArgument(1);
            BusinessResult<Void> result = work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(), result.resourceType(), result.resourceId(), null);
        });

        service.deleteMenuImage(11L, 7L, 13L, IdempotencyKey.parse("423e4567-e89b-42d3-a456-426614174000"));

        then(fileStorageFacade).should(org.mockito.Mockito.never())
                .markDeletedWithinCurrentTransaction(any(), any());
    }

    @Test
    void replayedPutDoesNotScanHistoricalDeletedImagesOnTheRequestPath() {
        UUID deletedImageId = UUID.fromString("523e4567-e89b-12d3-a456-426614174000");

        given(idempotencyExecutor.execute(any(), any())).willReturn(new IdempotentOutcome(
                true, 200, "SUCCESS", "MENU_IMAGE", "623e4567-e89b-12d3-a456-426614174000",
                JsonMapper.builder().build().createObjectNode().put(
                        "url", "/api/v1/public-files/623e4567-e89b-12d3-a456-426614174000")));
        service.putMenuImage(11L, 7L, 13L,
                IdempotencyKey.parse("723e4567-e89b-42d3-a456-426614174000"), pngFile());

        then(fileStorageFacade).should(org.mockito.Mockito.never()).delete(deletedImageId, NOW);
        then(storeService).should().requireManagementOwnership(11L, 7L);
    }

    @Test
    void replayedDeleteDoesNotRepeatHistoricalObjectCleanupOnTheRequestPath() {
        UUID imageId = UUID.fromString("823e4567-e89b-12d3-a456-426614174000");
        FileStorageMetadata confirmed = confirmedMenuImage(imageId).toPublicMetadata();

        given(menuRepository.findByIdForUpdate(13L)).willReturn(Optional.of(menu));
        given(menu.getStoreId()).willReturn(7L);
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(fileStorageFacade);
        given(fileStorageFacade.findPublicMetadata(any(), org.mockito.ArgumentMatchers.eq(FileStoragePurpose.MENU_IMAGE), any()))
                .willReturn(List.of(confirmed));
        given(idempotencyExecutor.execute(any(), any()))
                .willAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<BusinessResult<Void>> work = invocation.getArgument(1);
                    BusinessResult<Void> result = work.get();
                    return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(),
                            result.resourceType(), result.resourceId(), null);
                })
                .willReturn(new IdempotentOutcome(true, 204, "SUCCESS", "MENU_IMAGE", null, null));
        given(fileStorageFacade.delete(imageId, NOW))
                .willThrow(new com.miriyum.global.exception.ServiceException(
                        com.miriyum.global.exception.CommonErrorCode.SERVICE_UNAVAILABLE));

        TransactionSynchronizationManager.initSynchronization();
        service.deleteMenuImage(11L, 7L, 13L,
                IdempotencyKey.parse("923e4567-e89b-42d3-a456-426614174000"));
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.beforeCommit(false));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.clearSynchronization();

        service.deleteMenuImage(11L, 7L, 13L,
                IdempotencyKey.parse("923e4567-e89b-42d3-a456-426614174000"));

        then(fileStorageFacade).should().markDeletedWithinCurrentTransaction(imageId, NOW);
        then(fileStorageFacade).should().delete(imageId, NOW);
    }

    private FileMetadata confirmedMenuImage(UUID imageId) {
        FileMetadata metadata = menuImage(imageId);
        metadata.confirm();
        return metadata;
    }

    private FileMetadata menuImage(UUID imageId) {
        return FileMetadata.createPending(imageId.toString(), "MENU", 13L, FileStoragePurpose.MENU_IMAGE,
                "public/menus/13/images/one.png", "image/png", 8L, "0".repeat(64),
                FileStorageVisibility.PUBLIC, "MENU_IMAGE_PUBLIC", NOW);
    }

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "menu.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    }
}
