package com.miriyum.domain.platformoperator.adminstore.service;

import com.miriyum.domain.payment.service.StorePaymentImpactQueryService;
import com.miriyum.domain.pickup.service.StorePickupImpactQueryService;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.*;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.ImpactPreviewData;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionImpactPreview;
import com.miriyum.domain.platformoperator.adminstore.exception.AdminStoreErrorCode;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionImpactPreviewRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.platformoperator.enums.*;
import com.miriyum.domain.platformoperator.service.*;
import com.miriyum.domain.reservation.service.StoreReservationImpactQueryService;
import com.miriyum.domain.reservation.waiting.service.StoreWaitingImpactQueryService;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(prefix="miriyum.platform-operator",name="enabled",havingValue="true")
public class StoreSanctionImpactService {
    private final StoreSanctionCaseService cases; private final StoreAdministrationService stores;
    private final StoreReservationImpactQueryService reservations; private final StoreWaitingImpactQueryService waiting;
    private final StorePickupImpactQueryService pickups; private final StorePaymentImpactQueryService payments;
    private final StoreSanctionImpactPreviewRepository previews; private final Clock clock;
    private final OperatorAuthorityReader authorities; private final PlatformOperatorAuditWriter audit;
    public StoreSanctionImpactService(StoreSanctionCaseService cases, StoreAdministrationService stores,
            StoreReservationImpactQueryService reservations, StoreWaitingImpactQueryService waiting,
            StorePickupImpactQueryService pickups, StorePaymentImpactQueryService payments,
            StoreSanctionImpactPreviewRepository previews, OperatorAuthorityReader authorities,
            PlatformOperatorAuditWriter audit, Clock clock) {
        this.cases=cases; this.stores=stores; this.reservations=reservations; this.waiting=waiting;
        this.pickups=pickups; this.payments=payments; this.previews=previews; this.authorities=authorities;this.audit=audit;this.clock=clock;
    }
    @Transactional
    public ImpactPreviewData create(PlatformOperatorPrincipal principal, long storeId, String caseId,
                                    long caseVersion, SanctionShape shape,PlatformOperatorAuditReason reason,String correlation) {
        var c=cases.requireAssigned(principal, storeId, caseId, caseVersion);
        Instant now=clock.instant(); var state=stores.inspect(storeId); var impact=inspect(storeId,now);
        String fp=StoreSanctionFingerprint.shape(shape.type(), shape.restrictedFeatures(), shape.startsAt(), shape.endsAt());
        String digest=impact.digest(caseId,storeId,caseVersion,state.enforcementVersion(),fp);
        var data=previews.saveAndFlush(StoreSanctionImpactPreview.create(caseId, storeId, caseVersion,
                state.enforcementVersion(), impact.reservations().size(), impact.waiting().size(), impact.pickups().size(),
                impact.payments().size(), fp, digest, now.plus(Duration.ofMinutes(10)))).data();
        var authority=authorities.requireCurrentAuthority(principal.accountId(),principal.authorityVersion());
        audit.appendStore(new PlatformOperatorAuditWriter.StoreEvent(principal.accountId(),authority.authorityVersion(),
                authority.roles(),authority.permissions(),PlatformOperatorAuditAction.STORE_IMPACT_PREVIEWED,
                PlatformOperatorAuditOutcome.SUCCESS,reason,"STORE_IMPACT_PREVIEW",String.valueOf(data.previewId()),
                storeId,caseId,c.getCaseVersion(),null,null,state.enforcementVersion(),null,Map.of(),
                Map.of("preview",AdminStoreAuditSnapshots.preview(data),"store",AdminStoreAuditSnapshots.store(state)),correlation));return data;
    }
    @Transactional(readOnly = true)
    public void verify(long storeId, String caseId, ImpactConfirmation confirmation, SanctionShape shape) {
        Instant now=clock.instant(); var state=stores.inspect(storeId);
        var preview=previews.findById(confirmation.previewId())
                .orElseThrow(() -> new ServiceException(AdminStoreErrorCode.IMPACT_CONFIRMATION_REQUIRED));
        String fp=StoreSanctionFingerprint.shape(shape.type(), shape.restrictedFeatures(), shape.startsAt(), shape.endsAt());
        String currentDigest=inspect(storeId,now).digest(caseId,storeId,confirmation.caseVersion(),
                state.enforcementVersion(),fp);
        if (confirmation.caseVersion()!=preview.getCaseVersion()
                || confirmation.storeEnforcementVersion()!=state.enforcementVersion()
                || !currentDigest.equals(preview.getDigest())
                || !preview.matches(storeId, caseId, confirmation.caseVersion(), state.enforcementVersion(), fp,
                confirmation.previewDigest(), now)) {
            throw new ServiceException(AdminStoreErrorCode.IMPACT_CONFIRMATION_REQUIRED);
        }
    }
    private ImpactIds inspect(long storeId,Instant now){var r=reservations.inspect(storeId,now);var w=waiting.inspect(storeId);
        var p=pickups.inspect(storeId,now);var pay=payments.inspectReservationDeposits(r.reservationIds());
        return new ImpactIds(strings(r.reservationIds()),strings(w.waitingTeamIds()),strings(p.pickupIds()),
                pay.paymentIds().stream().toList());}
    private static List<String> strings(java.util.Set<Long> ids){return ids.stream().map(String::valueOf).toList();}
    private record ImpactIds(List<String> reservations,List<String> waiting,List<String> pickups,List<String> payments){
        String digest(String caseId,long storeId,long caseVersion,long enforcementVersion,String shape){
            return StoreSanctionFingerprint.impactDigest(caseId,storeId,caseVersion,enforcementVersion,shape,
                    reservations,waiting,pickups,payments);}}
}
