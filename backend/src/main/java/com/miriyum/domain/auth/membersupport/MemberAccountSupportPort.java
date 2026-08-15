package com.miriyum.domain.auth.membersupport;

import java.util.Optional;

public interface MemberAccountSupportPort {
    MemberAccountType accountType();

    Optional<MemberAccountSnapshot> findMinimal(long accountId);

    Optional<MemberAccountSnapshot> findRecoveryTarget(String oldEmail, String registeredPhone);

    default Optional<MemberAccountSnapshot> findRecoveryTarget(
            String oldEmail, String registeredPhone, String representativeName) {
        return representativeName == null ? findRecoveryTarget(oldEmail, registeredPhone) : Optional.empty();
    }

    default boolean matchesRegisteredContact(long accountId, MemberVerificationChannel channel, String contact) {
        return false;
    }

    MemberAccountPage search(MemberSearchCriteria criteria, MemberStatus status, int offset, int limit);

    long approveRecovery(long accountId, long expectedVersion, String newEmail);

    long applySuspension(long accountId, long expectedVersion);

    long clearSuspension(long accountId, long expectedVersion);

    default long advanceSupportVersion(long accountId, long expectedVersion) {
        throw new UnsupportedOperationException("support version CAS is required");
    }

    void replaceRecoveredPassword(long accountId, String newPassword);
}
