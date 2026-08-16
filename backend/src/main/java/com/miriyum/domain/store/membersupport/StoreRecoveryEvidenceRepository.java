package com.miriyum.domain.store.membersupport;

import com.miriyum.domain.store.entity.Store;
import org.springframework.data.repository.Repository;

interface StoreRecoveryEvidenceRepository extends Repository<Store, Long> {
    boolean existsByStoreOperatorAccountIdAndBusinessRegistrationNumber(
            Long storeOperatorAccountId, String businessRegistrationNumber);
}
