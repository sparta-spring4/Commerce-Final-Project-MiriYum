package com.miriyum.domain.auth.membersupport;

import java.util.Optional;

public interface MemberAccountSupportPort {
    MemberAccountType accountType();

    Optional<MemberAccountSnapshot> findMinimal(long accountId);

    Optional<MemberAccountSnapshot> findRecoveryTarget(String oldEmail, String registeredPhone);

    MemberAccountPage search(MemberSearchCriteria criteria, int offset, int limit);

    long approveRecovery(long accountId, long expectedVersion, String newEmail);

    long applySuspension(long accountId, long expectedVersion);

    long clearSuspension(long accountId, long expectedVersion);

    void replaceRecoveredPassword(long accountId, String newPassword);
}
