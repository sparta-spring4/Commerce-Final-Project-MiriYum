package com.miriyum.domain.platformoperator.adminstore.service;

import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionStatus;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionRepository;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 종료 시각이 지난 기간 제재만 복구하며 기존 거래 상태는 변경하지 않는다. */
@Service
@Slf4j
@ConditionalOnProperty(prefix="miriyum.platform-operator",name="enabled",havingValue="true")
public class StoreSanctionExpiryService {
    private final StoreSanctionRepository sanctions; private final StoreSanctionExpiryTransaction expiry; private final Clock clock;
    public StoreSanctionExpiryService(StoreSanctionRepository sanctions,StoreSanctionExpiryTransaction expiry,Clock clock){
        this.sanctions=sanctions;this.expiry=expiry;this.clock=clock;}
    @Scheduled(initialDelayString="${miriyum.platform-operator.store-sanction-expiry-initial-delay-ms:60000}",
            fixedDelayString="${miriyum.platform-operator.store-sanction-expiry-delay-ms:60000}",
            scheduler="storeSanctionTaskScheduler")
    public void run(){expireDue(100);}
    public int expireDue(int batchSize){if(batchSize<1||batchSize>100)throw new IllegalArgumentException("batchSize must be 1..100");
        int expired=0;var now=clock.instant();for(long id:sanctions.findDueExpiryIds(
                SanctionType.TEMPORARY_SUSPENSION,SanctionStatus.ACTIVE,now,PageRequest.of(0,batchSize))){
            try { if (expiry.expire(id,now)) expired++; }
            catch (RuntimeException exception) { log.warn("Store sanction expiry failed. sanctionId={}",id,exception); }
        }return expired;}
}
