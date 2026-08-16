package com.miriyum.domain.store.membersupport;

import com.miriyum.domain.auth.membersupport.StoreRecoveryEvidencePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StoreRecoveryEvidenceAdapter implements StoreRecoveryEvidencePort {
    private final StoreRecoveryEvidenceRepository stores;

    public StoreRecoveryEvidenceAdapter(StoreRecoveryEvidenceRepository stores) { this.stores = stores; }

    @Override @Transactional(readOnly = true)
    public boolean matchesOwnedStore(long accountId, String registrationNumber) {
        if (registrationNumber == null || registrationNumber.isBlank()) return false;
        return stores.existsByStoreOperatorAccountIdAndBusinessRegistrationNumber(
                accountId, registrationNumber.replace("-", "").trim());
    }
}
