package com.miriyum.domain.platformoperator.paymentrecovery.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryProposal;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuditEvent;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PaymentRecoveryAuditPrivacyTest {
    @Test
    void caseSnapshotUsesAClosedSafeFieldAllowlist() {
        PaymentRecoveryCase recoveryCase = PaymentRecoveryCase.open(
                "281", RecoveryKind.REFUND_RESULT_UNKNOWN, ResultStatus.UNKNOWN,
                300_000L, 100_000L, 200_000L, "KRW",
                Set.of(RecoveryAction.REQUERY_PROVIDER_RESULT), "port********abc",
                3L, 4L, 5L, Instant.parse("2026-08-19T10:00:00Z"));

        var snapshot = PaymentRecoveryAuditSnapshots.caseSnapshot(recoveryCase);

        assertThat(snapshot.keySet()).containsExactlyInAnyOrder(
                "caseId", "caseVersion", "status", "recoveryKind", "resultStatus",
                "originalAmountMinor", "cumulativeRefundedAmountMinor",
                "remainingRefundableAmountMinor", "currency", "maskedProviderReference",
                "allowedActions", "handoffVersion", "paymentVersion", "recoveryVersion");
        String json = new ObjectMapper().writeValueAsString(snapshot).toLowerCase();
        assertThat(json).doesNotContain("card", "account", "providerpayload", "authorization",
                "password", "approvaltoken", "secret", "portonepaymentid");
        assertThat(json).contains("port********abc");
    }

    @Test
    void recoveryAuditCarriesOnlyTheExplicitSnapshots() {
        var before = java.util.Map.<String, Object>of("status", "INVESTIGATING");
        var after = java.util.Map.<String, Object>of("status", "PROPOSED", "amountMinor", 100_000L);

        PlatformOperatorAuditEvent event = PlatformOperatorAuditEvent.createRecovery(
                11L, 7L, Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                PlatformOperatorAuditAction.PAYMENT_RECOVERY_PROPOSED,
                PlatformOperatorAuditOutcome.SUCCESS, PlatformOperatorAuditReason.PAYMENT_RECOVERY,
                "PAYMENT_RECOVERY_CASE", "550e8400-e29b-41d4-a716-446655440281",
                AdminCaseType.PAYMENT_RECOVERY, "550e8400-e29b-41d4-a716-446655440281", 3L,
                "550e8400-e29b-41d4-a716-446655440282", before, after, "corr-281",
                Instant.parse("2026-08-19T10:00:00Z"));

        assertThat(event.getBeforeSnapshot()).isEqualTo(before);
        assertThat(event.getAfterSnapshot()).isEqualTo(after);
        assertThat(event.getBeforeRoles()).isEmpty();
        assertThat(event.getAfterPermissions()).isEmpty();
    }

    @Test
    void proposalSnapshotIncludesMoneyTierAndActorWithoutSensitiveProviderData() {
        Instant now = Instant.parse("2026-08-19T10:00:00Z");
        PaymentRecoveryCase recoveryCase = PaymentRecoveryCase.open(
                "281", RecoveryKind.REFUND_FAILED, ResultStatus.FAILED,
                300_000L, 100_000L, 200_000L, "KRW", Set.of(RecoveryAction.RETRY_REFUND),
                "port********abc", 3L, 4L, 5L, now);
        recoveryCase.beginInvestigation(1L, now);
        PaymentRecoveryProposal proposal = PaymentRecoveryProposal.propose(
                recoveryCase.getPublicId(), 1L, 2L, RecoveryAction.RETRY_REFUND,
                100_001L, 200_001L, 300_000L, "KRW", 3L, 4L, 5L,
                "a".repeat(64), 11L, 7L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                java.util.UUID.randomUUID().toString(), now);

        var snapshot = PaymentRecoveryAuditSnapshots.proposalSnapshot(recoveryCase, proposal, 12L);

        assertThat(snapshot).containsEntry("requestedAmountMinor", 100_001L)
                .containsEntry("cumulativeLineageAmountMinor", 200_001L)
                .containsEntry("approvalTier", "ADDITIONAL_SUPER_ADMIN")
                .containsEntry("requesterOperatorId", 11L)
                .containsEntry("approverOperatorId", 12L);
        String json = new ObjectMapper().writeValueAsString(snapshot).toLowerCase();
        assertThat(json).doesNotContain("card", "account", "providerpayload", "authorization",
                "password", "approvaltoken", "secret", "portonepaymentid");
    }
}
