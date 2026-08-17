package com.miriyum.global.storage.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.service.FileMetadataTransactionExecutor;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileStorageReconciliationJobTest {

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    @DisplayName("삭제 실패 후보와 오래된 대기 후보를 합쳐 제한된 수만 재처리한다")
    void reconcilesDeletedAndStalePendingCandidatesWithinBatchLimit() {
        FileMetadataTransactionExecutor executor = mock(FileMetadataTransactionExecutor.class);
        FileStorageFacade facade = mock(FileStorageFacade.class);
        FileMetadata deleted = deletedMetadata();
        FileMetadata pending = pendingMetadata();
        when(executor.findObjectCleanupCandidates(2)).thenReturn(List.of(deleted));
        when(executor.findStalePendingCandidates(NOW.minusSeconds(600), 2)).thenReturn(List.of(pending));
        when(executor.discardStalePendingForReconciliation(eq(pending.getFileId()), eq(NOW)))
                .thenReturn(Optional.of(pending));
        when(executor.countLongStayCandidates(any(), any())).thenReturn(0L);

        FileStorageReconciliationJob.ReconciliationResult result = new FileStorageReconciliationJob(
                executor,
                facade,
                new FileStorageReconciliationProperties(true, 60_000, 4, 600, 3_600),
                Clock.fixed(NOW, ZoneOffset.UTC)).reconcile();

        assertThat(result).isEqualTo(new FileStorageReconciliationJob.ReconciliationResult(2, 2, 0, 0, 0));
        verify(facade).delete(UUID.fromString(deleted.getFileId()), NOW);
        verify(facade).delete(UUID.fromString(pending.getFileId()), NOW);
    }

    @Test
    @DisplayName("후보를 읽은 뒤 완료된 대기 파일은 객체 삭제 없이 건너뛴다")
    void skipsPendingCandidateThatWasConfirmedBeforeLocking() {
        FileMetadataTransactionExecutor executor = mock(FileMetadataTransactionExecutor.class);
        FileStorageFacade facade = mock(FileStorageFacade.class);
        FileMetadata pending = pendingMetadata();
        when(executor.findObjectCleanupCandidates(1)).thenReturn(List.of());
        when(executor.findStalePendingCandidates(NOW.minusSeconds(600), 1)).thenReturn(List.of(pending));
        when(executor.discardStalePendingForReconciliation(eq(pending.getFileId()), eq(NOW)))
                .thenReturn(Optional.empty());
        when(executor.countLongStayCandidates(any(), any())).thenReturn(0L);

        FileStorageReconciliationJob.ReconciliationResult result = new FileStorageReconciliationJob(
                executor,
                facade,
                new FileStorageReconciliationProperties(true, 60_000, 2, 600, 3_600),
                Clock.fixed(NOW, ZoneOffset.UTC)).reconcile();

        assertThat(result).isEqualTo(new FileStorageReconciliationJob.ReconciliationResult(1, 0, 1, 0, 0));
        org.mockito.Mockito.verifyNoInteractions(facade);
    }

    private FileMetadata pendingMetadata() {
        return FileMetadata.createPending(
                UUID.randomUUID().toString(), "STORE", 1L, FileStoragePurpose.STORE_IMAGE,
                "public/store/1/menu-image/pending", "image/jpeg", 1L, "a".repeat(64),
                FileStorageVisibility.PUBLIC, "STORE_IMAGE_DEFAULT", NOW.minusSeconds(1_000));
    }

    private FileMetadata deletedMetadata() {
        FileMetadata metadata = pendingMetadata();
        metadata.confirm();
        metadata.delete(NOW.minusSeconds(1_000));
        return metadata;
    }
}
