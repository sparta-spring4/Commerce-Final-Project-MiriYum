package com.miriyum.domain.auth.membersupport;

public interface StoreRecoveryEvidencePort {
    boolean matchesOwnedStore(long storeOperatorAccountId, String businessRegistrationNumber);
}
