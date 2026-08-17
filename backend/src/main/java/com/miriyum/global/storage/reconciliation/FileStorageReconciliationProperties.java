package com.miriyum.global.storage.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** S3 객체 정리 재시도의 제한과 관측 기준을 한곳에서 관리한다. */
@ConfigurationProperties("miriyum.storage.s3.reconciliation")
public record FileStorageReconciliationProperties(
        boolean enabled,
        long delayMs,
        int batchSize,
        long pendingMinAgeSeconds,
        long longStayThresholdSeconds,
        long retryBaseDelaySeconds,
        long claimLeaseSeconds
) {
}
