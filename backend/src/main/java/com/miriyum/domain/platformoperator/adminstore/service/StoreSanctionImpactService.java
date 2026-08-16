package com.miriyum.domain.platformoperator.adminstore.service;

import com.miriyum.domain.payment.service.StorePaymentImpactQueryService;
import com.miriyum.domain.pickup.service.StorePickupImpactQueryService;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.*;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.ImpactPreviewData;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionImpactPreview;
import com.miriyum.domain.platformoperator.adminstore.exception.AdminStoreErrorCode;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionImpactPreviewRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.reservation.service.StoreReservationImpactQueryService;
import com.miriyum.domain.reservation.waiting.service.StoreWaitingImpactQueryService;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
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
    public StoreSanctionImpactService(StoreSanctionCaseService cases, StoreAdministrationService stores,
            StoreReservationImpactQueryService reservations, StoreWaitingImpactQueryService waiting,
            StorePickupImpactQueryService pickups, StorePaymentImpactQueryService payments,
            StoreSanctionImpactPreviewRepository previews, Clock clock) {
        this.cases=cases; this.stores=stores; this.reservations=reservations; this.waiting=waiting;
        this.pickups=pickups; this.payments=payments; this.previews=previews; this.clock=clock;
    }
    @Transactional
    public ImpactPreviewData create(PlatformOperatorPrincipal principal, long storeId, String caseId,
                                    long caseVersion, SanctionShape shape) {
        cases.requireAssigned(principal, storeId, caseId, caseVersion);
        var state=stores.inspect(storeId); var r=reservations.inspect(storeId, clock.instant());
        var w=waiting.inspect(storeId); var p=pickups.inspect(storeId, clock.instant());
        var pay=payments.inspectReservationDeposits(r.reservationIds());
        String fp=StoreSanctionFingerprint.shape(shape.type(), shape.restrictedFeatures(), shape.startsAt(), shape.endsAt());
        String digest=StoreSanctionFingerprint.digest(caseId, storeId, caseVersion, state.enforcementVersion(), fp,
                r.confirmedCount(), w.activeTeamCount(), p.confirmedCount(), pay.unsettledCount());
        return previews.saveAndFlush(StoreSanctionImpactPreview.create(caseId, storeId, caseVersion,
                state.enforcementVersion(), r.confirmedCount(), w.activeTeamCount(), p.confirmedCount(),
                pay.unsettledCount(), fp, digest, clock.instant().plus(Duration.ofMinutes(10)))).data();
    }
    @Transactional(readOnly = true)
    public void verify(long storeId, String caseId, ImpactConfirmation confirmation, SanctionShape shape) {
        var state=stores.inspect(storeId);
        var preview=previews.findById(confirmation.previewId())
                .orElseThrow(() -> new ServiceException(AdminStoreErrorCode.IMPACT_CONFIRMATION_REQUIRED));
        String fp=StoreSanctionFingerprint.shape(shape.type(), shape.restrictedFeatures(), shape.startsAt(), shape.endsAt());
        if (confirmation.caseVersion()!=preview.getCaseVersion()
                || confirmation.storeEnforcementVersion()!=state.enforcementVersion()
                || !preview.matches(storeId, caseId, confirmation.caseVersion(), state.enforcementVersion(), fp,
                confirmation.previewDigest(), clock.instant())) {
            throw new ServiceException(AdminStoreErrorCode.IMPACT_CONFIRMATION_REQUIRED);
        }
    }
}
