package com.miriyum.domain.auth.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemberAccountSupportRegistryTest {

    @Test
    void selectsOnlyTheRequestedAccountTypePort() {
        MemberAccountSupportPort consumer = new StubPort(MemberAccountType.CONSUMER);
        MemberAccountSupportPort storeOperator = new StubPort(MemberAccountType.STORE_OPERATOR);
        MemberAccountSupportRegistry registry = new MemberAccountSupportRegistry(List.of(consumer, storeOperator));

        assertThat(registry.require(MemberAccountType.CONSUMER)).isSameAs(consumer);
        assertThat(registry.require(MemberAccountType.STORE_OPERATOR)).isSameAs(storeOperator);
    }

    @Test
    void rejectsDuplicatePortsInsteadOfPickingOneByOrder() {
        assertThatThrownBy(() -> new MemberAccountSupportRegistry(List.of(
                new StubPort(MemberAccountType.CONSUMER), new StubPort(MemberAccountType.CONSUMER))))
                .isInstanceOf(IllegalStateException.class);
    }

    private record StubPort(MemberAccountType accountType) implements MemberAccountSupportPort {
        @Override public Optional<MemberAccountSnapshot> findMinimal(long accountId) { return Optional.empty(); }
        @Override public Optional<MemberAccountSnapshot> findRecoveryTarget(String email, String phone) {
            return Optional.empty();
        }
        @Override public MemberAccountPage search(MemberSearchCriteria criteria, MemberStatus status,
                                                  int offset, int limit) {
            return new MemberAccountPage(List.of(), 0);
        }
        @Override public long approveRecovery(long accountId, long expectedVersion, String newEmail) { return 0; }
        @Override public long applySuspension(long accountId, long expectedVersion) { return 0; }
        @Override public long clearSuspension(long accountId, long expectedVersion) { return 0; }
        @Override public void replaceRecoveredPassword(long accountId, String newPassword) { }
    }
}
