package com.miriyum.domain.auth.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.Test;

class MemberAccountGuardTest {

    @Test
    void recoveryApprovalChangesEmailBlocksLoginAndAdvancesConsumerVersion() {
        ConsumerAccount account = ConsumerAccount.create("old@example.com", "old-hash", "consumer");

        account.assertSupportVersion(0);
        account.approveRecovery("new@example.com");

        assertThat(account.getEmail()).isEqualTo("new@example.com");
        assertThat(account.isPasswordResetRequired()).isTrue();
        assertThat(account.getSupportVersion()).isEqualTo(1);
    }

    @Test
    void staleStoreOperatorSupportVersionUsesStableConflictCode() {
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hash", "owner");
        account.approveRecovery("recovered@example.com");

        assertThatThrownBy(() -> account.assertSupportVersion(0))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT));
    }

    @Test
    void recoveredPasswordClearsOnlyTheResetRequirement() {
        ConsumerAccount account = ConsumerAccount.create("old@example.com", "old-hash", "consumer");
        account.approveRecovery("new@example.com");

        account.replaceRecoveredPassword("new-hash");

        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.isPasswordResetRequired()).isFalse();
        assertThat(account.getSupportVersion()).isEqualTo(2);
    }
}
