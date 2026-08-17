package com.miriyum.global.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStorageOwner;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileStorageReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T08:00:00Z");

    @Test
    void recoversStalePendingAndLogicalDeletesInSeparateBatches() {
        FileStorageFacade facade = mock(FileStorageFacade.class);
        FileMetadataTransactionExecutor executor = mock(FileMetadataTransactionExecutor.class);
        FileStorageMetadata pending = metadata(FileStorageStatus.PENDING, null);
        FileStorageMetadata deleted = metadata(FileStorageStatus.DELETED, NOW.minus(Duration.ofMinutes(10)));
        when(executor.findStalePending(NOW.minus(Duration.ofMinutes(5)), 25)).thenReturn(List.of(pending));
        when(executor.findPendingCleanup(25)).thenReturn(List.of(deleted));
        FileStorageReconciliationService service = new FileStorageReconciliationService(
                facade, executor, Clock.fixed(NOW, ZoneOffset.UTC));

        FileStorageReconciliationService.ReconciliationResult result =
                service.reconcile(25, Duration.ofMinutes(5));

        assertThat(result.stalePendingRecovered()).isEqualTo(1);
        assertThat(result.deletedObjectsRecovered()).isEqualTo(1);
        verify(facade).discardPending(pending.fileId(), NOW);
        verify(facade).delete(deleted.fileId(), NOW);
    }

    @Test
    void continuesTheBatchWhenOneObjectStoreCleanupFails() {
        FileStorageFacade facade = mock(FileStorageFacade.class);
        FileMetadataTransactionExecutor executor = mock(FileMetadataTransactionExecutor.class);
        FileStorageMetadata first = metadata(FileStorageStatus.DELETED, NOW.minus(Duration.ofMinutes(10)));
        FileStorageMetadata second = metadata(FileStorageStatus.DELETED, NOW.minus(Duration.ofMinutes(9)));
        when(executor.findStalePending(any(), any(Integer.class))).thenReturn(List.of());
        when(executor.findPendingCleanup(25)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("object store unavailable"))
                .when(facade).delete(first.fileId(), NOW);
        FileStorageReconciliationService service = new FileStorageReconciliationService(
                facade, executor, Clock.fixed(NOW, ZoneOffset.UTC));

        FileStorageReconciliationService.ReconciliationResult result =
                service.reconcile(25, Duration.ofMinutes(5));

        assertThat(result.deletedObjectsRecovered()).isEqualTo(1);
        verify(facade).delete(first.fileId(), NOW);
        verify(facade).delete(second.fileId(), NOW);
    }

    private static FileStorageMetadata metadata(FileStorageStatus status, Instant deletedAt) {
        return new FileStorageMetadata(
                UUID.randomUUID(),
                new FileStorageOwner("MENU", 7L),
                FileStoragePurpose.MENU_IMAGE,
                "public/menus/7/images/" + UUID.randomUUID() + ".png",
                "image/png",
                42L,
                "a".repeat(64),
                FileStorageVisibility.PUBLIC,
                status,
                "MENU_IMAGE_PUBLIC",
                NOW.minus(Duration.ofHours(1)),
                deletedAt);
    }
}
