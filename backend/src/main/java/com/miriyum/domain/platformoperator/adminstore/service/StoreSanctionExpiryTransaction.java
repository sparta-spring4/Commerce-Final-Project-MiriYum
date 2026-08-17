package com.miriyum.domain.platformoperator.adminstore.service;

import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionStatus;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionRepository;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionCaseRepository;
import com.miriyum.domain.platformoperator.enums.*;
import com.miriyum.domain.platformoperator.service.*;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.ReleaseCommand;
import com.miriyum.domain.store.service.StoreAdministrationService;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix="miriyum.platform-operator",name="enabled",havingValue="true")
public class StoreSanctionExpiryTransaction {
    private final StoreSanctionRepository sanctions;
    private final StoreAdministrationService stores;
    private final StoreSanctionCaseRepository cases;
    private final OperatorAuthorityReader authorities;
    private final PlatformOperatorAuditWriter audit;

    public StoreSanctionExpiryTransaction(StoreSanctionRepository sanctions, StoreAdministrationService stores,
            StoreSanctionCaseRepository cases, OperatorAuthorityReader authorities, PlatformOperatorAuditWriter audit) {
        this.sanctions = sanctions;
        this.stores = stores;
        this.cases = cases;
        this.authorities = authorities;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(long id, Instant now) {
        var seed = sanctions.findById(id).orElse(null);
        if (seed == null) return false;
        var sanction = sanctions.findScopedForUpdate(id, seed.getCaseId(), seed.getStoreId()).orElse(null);
        if (sanction == null || sanction.getStatus() != SanctionStatus.ACTIVE) return false;
        var storeBefore=stores.inspect(sanction.getStoreId());var sanctionBefore=sanction.data();
        var result = stores.release(new ReleaseCommand(sanction.getStoreId(), id));
        sanction.expire(sanction.getSanctionVersion(), result.enforcementVersion(), now);
        var c=cases.findByPublicIdAndStoreId(sanction.getCaseId(),sanction.getStoreId()).orElseThrow();
        var authority=authorities.currentAuthority(sanction.getCreatedBy());
        audit.appendStore(new PlatformOperatorAuditWriter.StoreEvent(sanction.getCreatedBy(),authority.authorityVersion(),
                authority.roles(),authority.permissions(),PlatformOperatorAuditAction.STORE_SANCTION_EXPIRED,
                PlatformOperatorAuditOutcome.SUCCESS,PlatformOperatorAuditReason.STORE_ENFORCEMENT,"STORE_SANCTION",
                String.valueOf(id),sanction.getStoreId(),sanction.getCaseId(),c.getCaseVersion(),id,
                sanction.getSanctionVersion(),result.enforcementVersion(),null,
                Map.of("automation",true,"sanction",AdminStoreAuditSnapshots.sanction(sanctionBefore),
                        "store",AdminStoreAuditSnapshots.store(storeBefore)),
                Map.of("automation",true,"sanction",AdminStoreAuditSnapshots.sanction(sanction.data()),
                        "store",AdminStoreAuditSnapshots.store(result)),"store-sanction-expiry:"+id+":"+sanction.getSanctionVersion()));
        return true;
    }
}
