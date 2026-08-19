package com.miriyum.domain.platformoperator.paymentrecovery.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ApprovalTier;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.exception.PaymentRecoveryErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentRecoveryProposalTest {
    private static final String CASE_ID = "550e8400-e29b-41d4-a716-446655440281";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440282";
    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Test
    void cumulativeTwoHundredThousandWonIsSingleOperatorApproval() {
        PaymentRecoveryProposal proposal = proposal(100_000L, 200_000L, 300_000L);

        assertThat(proposal.getApprovalTier()).isEqualTo(ApprovalTier.SINGLE_OPERATOR);
        PaymentRecoveryApproval approval = PaymentRecoveryApproval.approve(
                proposal, 11L, 7L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE), KEY, NOW);

        assertThat(approval.getRequesterPlatformOperatorAccountId()).isEqualTo(11L);
        assertThat(approval.getApproverPlatformOperatorAccountId()).isEqualTo(11L);
    }

    @Test
    void cumulativeTwoHundredThousandAndOneWonRequiresDifferentSuperAdmin() {
        PaymentRecoveryProposal proposal = proposal(100_001L, 200_001L, 300_000L);

        assertThat(proposal.getApprovalTier()).isEqualTo(ApprovalTier.ADDITIONAL_SUPER_ADMIN);
        assertThatThrownBy(() -> PaymentRecoveryApproval.approve(
                proposal, 11L, 8L, Set.of(PlatformOperatorRole.SUPER_ADMIN),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE), KEY, NOW))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentRecoveryErrorCode.RECOVERY_APPROVER_MUST_DIFFER));

        PaymentRecoveryApproval approval = PaymentRecoveryApproval.approve(
                proposal, 22L, 8L, Set.of(PlatformOperatorRole.SUPER_ADMIN),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE), KEY, NOW);
        assertThat(approval.getApproverPlatformOperatorAccountId()).isEqualTo(22L);
    }

    @Test
    void amountAboveOriginalIsRejectedBecausePaymentHasNoCompensationContract() {
        assertThatThrownBy(() -> proposal(200_001L, 300_001L, 300_000L))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                PaymentRecoveryErrorCode.RECOVERY_COMPENSATION_NOT_SUPPORTED));
    }

    private static PaymentRecoveryProposal proposal(
            long requested,
            long cumulative,
            long original
    ) {
        return PaymentRecoveryProposal.propose(
                CASE_ID, 1L, RecoveryAction.RETRY_REFUND,
                requested, cumulative, original, "KRW",
                3L, 4L, 5L, "a".repeat(64),
                11L, 7L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                KEY, NOW);
    }
}
