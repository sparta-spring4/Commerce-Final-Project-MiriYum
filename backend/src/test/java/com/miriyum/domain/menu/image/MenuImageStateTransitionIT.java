package com.miriyum.domain.menu.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStorageObject;
import com.miriyum.global.storage.FileStorageOwner;
import com.miriyum.global.storage.FileStoragePort;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageSaveResult;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import com.miriyum.global.storage.service.FileMetadataTransactionExecutor;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** 메뉴 이미지 서비스의 트랜잭션 동기화가 실제 파일 메타데이터 상태와 함께 수렴하는지 검증한다. */
@Tag("integration")
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes")
class MenuImageStateTransitionIT {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final long STORE_ID = 7L;
    private static final AtomicLong MENU_ID_SEQUENCE = new AtomicLong(13L);
    private static final long OPERATOR_ID = 11L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private FileMetadataTransactionExecutor transactionExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    private FileMetadataRepository fileMetadataRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private PlatformTransactionManager transactionManager;

    private RecordingFileStoragePort storagePort;
    private MenuImageService service;
    private long menuId;

    @BeforeEach
    void setUp() {
        menuId = MENU_ID_SEQUENCE.getAndIncrement();
        storagePort = new RecordingFileStoragePort();
        FileStorageFacade facade = new FileStorageFacade(storagePort, transactionExecutor);
        @SuppressWarnings("unchecked")
        ObjectProvider<FileStorageFacade> facadeProvider = mock(ObjectProvider.class);
        given(facadeProvider.getIfAvailable()).willReturn(facade);

        MenuRepository menuRepository = mock(MenuRepository.class);
        Menu menu = mock(Menu.class);
        given(menuRepository.findByIdForUpdate(menuId)).willReturn(Optional.of(menu));
        given(menu.getStoreId()).willReturn(STORE_ID);

        IdempotencyExecutor idempotencyExecutor = mock(IdempotencyExecutor.class);
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<BusinessResult<MenuPublicImageResponse>> work = invocation.getArgument(1);
            BusinessResult<MenuPublicImageResponse> result = work.get();
            return new IdempotentOutcome(
                    false, result.httpStatus(), result.responseCode(), result.resourceType(),
                    result.resourceId(), JsonMapper.builder().build().valueToTree(result.data()));
        });

        service = new MenuImageService(
                mock(StoreService.class), menuRepository, facadeProvider, idempotencyExecutor,
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "maximumSizeBytes", 10 * 1024 * 1024L);
    }

    @Test
    @DisplayName("교체를 커밋하면 새 이미지는 CONFIRMED, 모든 기존 이미지는 DELETED로 함께 수렴한다")
    void replacesEveryConfirmedImageWithinTheOwnerTransaction() {
        UUID first = confirmedImage("first");
        UUID second = confirmedImage("second");

        MenuImageCommandResult result = transaction().execute(status -> service.putMenuImage(
                OPERATOR_ID, STORE_ID, menuId, idempotencyKey(), pngFile()));
        UUID replacement = imageId(result);

        assertStatus(first, FileStorageStatus.DELETED);
        assertStatus(second, FileStorageStatus.DELETED);
        assertStatus(replacement, FileStorageStatus.CONFIRMED);
        assertThat(storagePort.deletedObjectKeys()).containsExactlyInAnyOrder(
                objectKey(first), objectKey(second));
    }

    @Test
    @DisplayName("교체 작업이 롤백되면 기존 CONFIRMED 이미지는 유지하고 새 PENDING 이미지는 보상 삭제한다")
    void keepsPreviousImageAndCompensatesPendingImageWhenOwnerTransactionRollsBack() {
        UUID previous = confirmedImage("previous");

        transaction().executeWithoutResult(status -> {
            service.putMenuImage(OPERATOR_ID, STORE_ID, menuId, idempotencyKey(), pngFile());
            status.setRollbackOnly();
        });

        assertStatus(previous, FileStorageStatus.CONFIRMED);
        List<FileMetadata> images = fileMetadataRepository
                .findAllByOwnerTypeAndOwnerIdAndPurposeAndVisibilityAndStorageStatusInOrderByCreatedAtAsc(
                        "MENU", menuId, FileStoragePurpose.MENU_IMAGE,
                        FileStorageVisibility.PUBLIC, List.of(FileStorageStatus.DELETED));
        assertThat(images).singleElement().satisfies(image -> {
            assertThat(image.getStorageStatus()).isEqualTo(FileStorageStatus.DELETED);
            assertThat(storagePort.deletedObjectKeys()).contains(image.getObjectKey());
        });
    }

    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // MenuImageService uses READ_COMMITTED so it can see the PENDING row saved in REQUIRES_NEW.
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private UUID confirmedImage(String suffix) {
        UUID imageId = UUID.randomUUID();
        transactionExecutor.savePending(FileMetadata.createPending(
                imageId.toString(), "MENU", menuId, FileStoragePurpose.MENU_IMAGE,
                objectKey(imageId), "image/png", 8L, "0".repeat(64),
                FileStorageVisibility.PUBLIC, "MENU_IMAGE_PUBLIC", NOW.minusSeconds(60)));
        transactionExecutor.confirm(imageId.toString());
        return imageId;
    }

    private void assertStatus(UUID imageId, FileStorageStatus status) {
        assertThat(fileMetadataRepository.findById(imageId.toString()))
                .isPresent()
                .get()
                .extracting(FileMetadata::getStorageStatus)
                .isEqualTo(status);
    }

    private static UUID imageId(MenuImageCommandResult result) {
        String url = result.data().url();
        return UUID.fromString(url.substring(url.lastIndexOf('/') + 1));
    }

    private static IdempotencyKey idempotencyKey() {
        return IdempotencyKey.parse(UUID.randomUUID().toString());
    }

    private String objectKey(UUID imageId) {
        return "public/menus/" + menuId + "/images/" + imageId + ".png";
    }

    private static MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "menu.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    }

    private static final class RecordingFileStoragePort implements FileStoragePort {

        private final java.util.ArrayList<String> deletedObjectKeys = new java.util.ArrayList<>();

        @Override
        public FileStorageSaveResult save(FileStorageRequest request) {
            try {
                return new FileStorageSaveResult(
                        request.objectKey(), request.contentType(), request.sizeBytes(),
                        checksum(request.content().readAllBytes()));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public FileStorageObject read(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            deletedObjectKeys.add(objectKey);
        }

        private List<String> deletedObjectKeys() {
            return List.copyOf(deletedObjectKeys);
        }

        private static String checksum(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

}
