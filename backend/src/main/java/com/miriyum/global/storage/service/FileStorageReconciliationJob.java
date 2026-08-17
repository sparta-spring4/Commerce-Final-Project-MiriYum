package com.miriyum.global.storage.service;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** S3 runtime이 켜진 환경에서만 파일 객체 대사를 실행한다. */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "miriyum.storage.s3", name = "enabled", havingValue = "true")
public class FileStorageReconciliationJob {

    private final FileStorageReconciliationService reconciliationService;
    private final int batchSize;
    private final Duration pendingAge;

    public FileStorageReconciliationJob(
            FileStorageReconciliationService reconciliationService,
            @Value("${miriyum.storage.s3.reconciliation-batch-size:25}") int batchSize,
            @Value("${miriyum.storage.s3.reconciliation-pending-age:PT5M}") Duration pendingAge
    ) {
        if (batchSize <= 0 || pendingAge == null || pendingAge.isNegative() || pendingAge.isZero()) {
            throw new IllegalArgumentException("S3 reconciliation settings must be positive");
        }
        this.reconciliationService = reconciliationService;
        this.batchSize = batchSize;
        this.pendingAge = pendingAge;
    }

    @Scheduled(fixedDelayString = "${miriyum.storage.s3.reconciliation-delay-ms:60000}")
    public FileStorageReconciliationService.ReconciliationResult reconcile() {
        FileStorageReconciliationService.ReconciliationResult result =
                reconciliationService.reconcile(batchSize, pendingAge);
        if (result.totalRecovered() > 0) {
            log.info(
                    "event=file_storage_reconciliation_succeeded stale_pending_recovered={} deleted_objects_recovered={}",
                    result.stalePendingRecovered(), result.deletedObjectsRecovered());
        }
        return result;
    }
}
