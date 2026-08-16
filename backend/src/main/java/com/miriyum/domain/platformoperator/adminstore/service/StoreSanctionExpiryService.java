package com.miriyum.domain.platformoperator.adminstore.service;

import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionStatus;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionRepository;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.ReleaseCommand;
import com.miriyum.domain.store.service.StoreAdministrationService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 종료 시각이 지난 기간 제재만 복구하며 기존 거래 상태는 변경하지 않는다. */
@Service
@ConditionalOnProperty(prefix="miriyum.platform-operator",name="enabled",havingValue="true")
public class StoreSanctionExpiryService {
    private final StoreSanctionRepository sanctions; private final StoreAdministrationService stores; private final Clock clock;
    public StoreSanctionExpiryService(StoreSanctionRepository sanctions,StoreAdministrationService stores,Clock clock){
        this.sanctions=sanctions;this.stores=stores;this.clock=clock;}
    @Scheduled(initialDelayString="${miriyum.platform-operator.store-sanction-expiry-initial-delay-ms:60000}",
            fixedDelayString="${miriyum.platform-operator.store-sanction-expiry-delay-ms:60000}")
    @Transactional
    public void run(){expireDue(100);}
    @Transactional
    public int expireDue(int batchSize){if(batchSize<1||batchSize>100)throw new IllegalArgumentException("batchSize must be 1..100");
        int expired=0;var now=clock.instant();for(long id:sanctions.findDueExpiryIds(
                SanctionType.TEMPORARY_SUSPENSION,SanctionStatus.ACTIVE,now,PageRequest.of(0,batchSize))){
            var seed=sanctions.findById(id).orElse(null);if(seed==null)continue;
            var sanction=sanctions.findScopedForUpdate(id,seed.getCaseId(),seed.getStoreId()).orElse(null);
            if(sanction==null||sanction.getStatus()!=SanctionStatus.ACTIVE)continue;
            var result=stores.release(new ReleaseCommand(sanction.getStoreId(),sanction.getStoreEnforcementVersion(),id));
            sanction.expire(sanction.getSanctionVersion(),result.enforcementVersion(),now);expired++;}return expired;}
}
