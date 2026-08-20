package com.miriyum.domain.store.evidence.entity;

import com.miriyum.domain.store.evidence.enums.BusinessRegistrationEvidenceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 입점 신청 version이 참조하는 비공개 사업자등록증 원장이다.
 *
 * <p>파일 원본의 객체 키와 URL은 공용 파일 메타데이터에만 남기고, 이 aggregate는 심사 workflow가 사용할
 * 불투명 evidenceId와 상태·보존 시각만 보관한다.</p>
 */
@Entity
@Table(name = "store_business_registration_evidences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusinessRegistrationEvidence {

    @Id
    @Column(name = "evidence_id", nullable = false, length = 36)
    private String evidenceId;

    @Column(name = "onboarding_application_id", nullable = false)
    private long onboardingApplicationId;

    @Column(name = "application_version", nullable = false)
    private long applicationVersion;

    @Column(name = "store_operator_account_id", nullable = false)
    private long storeOperatorAccountId;

    @Column(name = "file_id", nullable = false, length = 36, unique = true)
    private String fileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_status", nullable = false, length = 20)
    private BusinessRegistrationEvidenceStatus evidenceStatus;

    /** 현재 증빙만 1을 가지며, 교체된 이력은 NULL이라 여러 version 이력을 보존할 수 있다. */
    @Column(name = "current_marker")
    private Integer currentMarker;

    @Column(name = "retention_due_at")
    private Instant retentionDueAt;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    private BusinessRegistrationEvidence(
            UUID evidenceId,
            long onboardingApplicationId,
            long applicationVersion,
            long storeOperatorAccountId,
            UUID fileId,
            Instant createdAt
    ) {
        requirePositive(onboardingApplicationId, "onboarding application id");
        requirePositive(applicationVersion, "application version");
        requirePositive(storeOperatorAccountId, "store operator account id");
        if (evidenceId == null || fileId == null || createdAt == null) {
            throw new IllegalArgumentException("evidence id, file id, and created at are required");
        }
        this.evidenceId = evidenceId.toString();
        this.onboardingApplicationId = onboardingApplicationId;
        this.applicationVersion = applicationVersion;
        this.storeOperatorAccountId = storeOperatorAccountId;
        this.fileId = fileId.toString();
        this.evidenceStatus = BusinessRegistrationEvidenceStatus.CURRENT;
        this.currentMarker = 1;
        this.createdAt = createdAt;
    }

    public static BusinessRegistrationEvidence createCurrent(
            UUID evidenceId,
            long onboardingApplicationId,
            long applicationVersion,
            long storeOperatorAccountId,
            UUID fileId,
            Instant createdAt
    ) {
        return new BusinessRegistrationEvidence(
                evidenceId,
                onboardingApplicationId,
                applicationVersion,
                storeOperatorAccountId,
                fileId,
                createdAt);
    }

    /** 새 증빙이 확정되는 즉시 구버전 접근을 차단하고 7일 보존 기한을 남긴다. */
    public void replace(Instant replacedAt, Instant retentionDueAt) {
        if (!isCurrentEvidence() || evidenceStatus != BusinessRegistrationEvidenceStatus.CURRENT) {
            throw new IllegalStateException("current evidence can only be replaced once");
        }
        if (replacedAt == null || retentionDueAt == null || retentionDueAt.isBefore(replacedAt)) {
            throw new IllegalArgumentException("replacement retention window is invalid");
        }
        this.currentMarker = null;
        this.evidenceStatus = BusinessRegistrationEvidenceStatus.REPLACED;
        this.replacedAt = replacedAt;
        this.retentionDueAt = retentionDueAt;
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    public boolean isCurrentEvidence() {
        return currentMarker != null;
    }
}
