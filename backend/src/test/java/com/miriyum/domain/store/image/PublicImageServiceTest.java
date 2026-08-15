package com.miriyum.domain.store.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class PublicImageServiceTest {

    @Mock
    private StoreService storeService;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @Mock
    private ObjectProvider<FileStorageFacade> fileStorageFacadeProvider;

    @Mock
    private ObjectProvider<com.miriyum.global.storage.FileStoragePort> fileStoragePortProvider;

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
}
