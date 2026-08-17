package com.miriyum.global.storage.reconciliation;

import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.service.FileMetadataTransactionExecutor;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 논리 삭제 후 남은 객체와 오래된 PENDING 메타데이터를 제한된 배치로 재처리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "miriyum.storage.s3",
        name = {"enabled", "reconciliation.enabled"},
        havingValue = "true")
public class FileStorageReconciliationJob {

    private final FileMetadataTransactionExecutor transactionExecutor;
    private final FileStorageFacade fileStorageFacade;
    private final FileStorageReconciliationProperties properties;
    private final Clock clock;

    @Scheduled(
            fixedDelayString = "${miriyum.storage.s3.reconciliation.delay-ms:60000}",
            scheduler = "fileStorageReconciliationTaskScheduler")
    public void reconcileOnSchedule() {
        reconcile();
    }

    public ReconciliationResult reconcile() {
        validateProperties();
        Instant now = clock.instant();
        int limitPerType = Math.max(1, properties.batchSize() / 2);
        int attempted = 0;
        int completed = 0;
        int skipped = 0;
        int failed = 0;

        for (FileMetadata candidate : transactionExecutor.findObjectCleanupCandidates(now, limitPerType)) {
            var claimed = transactionExecutor.claimObjectCleanup(
                    candidate.getFileId(), now, now.plusSeconds(properties.claimLeaseSeconds()));
            if (claimed.isEmpty()) {
                skipped++;
                continue;
            }
            FileMetadata metadata = claimed.get();
            try {
                fileStorageFacade.deleteForReconciliation(
                        metadata.toPublicMetadata(), metadata.getObjectCleanupClaimToken(), now);
                completed++;
            } catch (RuntimeException exception) {
                rescheduleClaimedCleanup(metadata, now);
                failed++;
            }
        }

        Instant stalePendingBefore = now.minusSeconds(properties.pendingMinAgeSeconds());
        for (FileMetadata candidate : transactionExecutor.findStalePendingCandidates(stalePendingBefore, limitPerType)) {
            FileMetadata claimedMetadata = null;
            try {
                var deleted = transactionExecutor.discardStalePendingForReconciliation(candidate.getFileId(), now);
                if (deleted.isEmpty()) {
                    skipped++;
                    continue;
                }
                var claimed = transactionExecutor.claimObjectCleanup(
                        deleted.get().getFileId(), now, now.plusSeconds(properties.claimLeaseSeconds()));
                if (claimed.isEmpty()) {
                    skipped++;
                    continue;
                }
                attempted++;
                claimedMetadata = claimed.get();
                fileStorageFacade.deleteForReconciliation(
                        claimedMetadata.toPublicMetadata(), claimedMetadata.getObjectCleanupClaimToken(), now);
                completed++;
            } catch (RuntimeException exception) {
                if (claimedMetadata != null) {
                    rescheduleClaimedCleanup(claimedMetadata, now);
                }
                failed++;
            }
        }

        long longStayCount = transactionExecutor.countLongStayCandidates(
                now.minusSeconds(properties.longStayThresholdSeconds()),
                now.minusSeconds(properties.longStayThresholdSeconds()));
        log.info("event=file_storage_reconciliation_completed attempted={} completed={} skipped={} failed={}",
                attempted, completed, skipped, failed);
        if (failed > 0) {
            log.warn("event=file_storage_reconciliation_failed failure_count={}", failed);
        }
        if (longStayCount > 0) {
            log.warn("event=file_storage_reconciliation_long_stay long_stay_count={}", longStayCount);
        }
        return new ReconciliationResult(attempted, completed, skipped, failed, longStayCount);
    }

    private void validateProperties() {
        if (properties.delayMs() <= 0
                || properties.batchSize() < 2
                || properties.pendingMinAgeSeconds() < 0
                || properties.longStayThresholdSeconds() <= 0
                || properties.retryBaseDelaySeconds() <= 0
                || properties.claimLeaseSeconds() <= 0) {
            throw new IllegalStateException("파일 저장소 정리 재시도 설정값이 올바르지 않습니다.");
        }
    }

    /** 재시도 시각 기록까지 실패해도 lease 만료 뒤 후속 주기가 회수할 수 있으므로 batch를 계속 진행한다. */
    private void rescheduleClaimedCleanup(FileMetadata metadata, Instant now) {
        try {
            transactionExecutor.rescheduleClaimedObjectCleanup(
                    metadata.getFileId(), metadata.getObjectCleanupClaimToken(), now,
                    properties.retryBaseDelaySeconds());
        } catch (RuntimeException ignored) {
            // 식별자·SDK 예외는 남기지 않고 마지막 집계 failed 수로만 관측한다.
        }
    }

    public record ReconciliationResult(int attempted, int completed, int skipped, int failed, long longStayCount) {
    }
}
