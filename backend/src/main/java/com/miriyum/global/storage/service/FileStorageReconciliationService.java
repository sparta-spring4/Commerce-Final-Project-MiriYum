package com.miriyum.global.storage.service;

import com.miriyum.global.storage.FileStorageMetadata;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 오래된 미확정 객체와 물리 삭제 실패를 요청 경로 밖에서 제한적으로 회수한다. */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "miriyum.storage.s3", name = "enabled", havingValue = "true")
public class FileStorageReconciliationService {

    private final FileStorageFacade fileStorageFacade;
    private final FileMetadataTransactionExecutor transactionExecutor;
    private final Clock clock;

    public ReconciliationResult reconcile(int batchSize, Duration pendingAge) {
        if (batchSize <= 0 || pendingAge == null || pendingAge.isNegative() || pendingAge.isZero()) {
            throw new IllegalArgumentException("batch size and pending age must be positive");
        }
        Instant now = clock.instant();
        int pendingSuccess = reconcileStalePending(batchSize, now.minus(pendingAge), now);
        int cleanupSuccess = reconcileDeleted(batchSize, now);
        return new ReconciliationResult(pendingSuccess, cleanupSuccess);
    }

    private int reconcileStalePending(int batchSize, Instant cutoff, Instant now) {
        int success = 0;
        for (FileStorageMetadata metadata : transactionExecutor.findStalePending(cutoff, batchSize)) {
            try {
                fileStorageFacade.discardPending(metadata.fileId(), now);
                success++;
            } catch (RuntimeException exception) {
                log.warn("event=file_storage_reconciliation_retryable_failure stage=stale_pending");
            }
        }
        return success;
    }

    private int reconcileDeleted(int batchSize, Instant now) {
        int success = 0;
        for (FileStorageMetadata metadata : transactionExecutor.findPendingCleanup(batchSize)) {
            try {
                fileStorageFacade.delete(metadata.fileId(), now);
                success++;
            } catch (RuntimeException exception) {
                log.warn("event=file_storage_reconciliation_retryable_failure stage=deleted_cleanup");
            }
        }
        return success;
    }

    public record ReconciliationResult(int stalePendingRecovered, int deletedObjectsRecovered) {
        public int totalRecovered() {
            return stalePendingRecovered + deletedObjectsRecovered;
        }
    }
}
