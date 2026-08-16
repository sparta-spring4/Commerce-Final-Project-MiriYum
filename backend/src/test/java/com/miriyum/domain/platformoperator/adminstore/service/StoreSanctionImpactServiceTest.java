package com.miriyum.domain.platformoperator.adminstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.payment.dto.StorePaymentImpact;
import com.miriyum.domain.payment.service.StorePaymentImpactQueryService;
import com.miriyum.domain.pickup.dto.StorePickupImpact;
import com.miriyum.domain.pickup.service.StorePickupImpactQueryService;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.ImpactConfirmation;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.SanctionShape;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionImpactPreview;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionImpactPreviewRepository;
import com.miriyum.domain.reservation.dto.StoreReservationImpact;
import com.miriyum.domain.reservation.service.StoreReservationImpactQueryService;
import com.miriyum.domain.reservation.waiting.dto.StoreWaitingImpact;
import com.miriyum.domain.reservation.waiting.service.StoreWaitingImpactQueryService;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementResult;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StoreSanctionImpactServiceTest {

    @Test
    void digestChangesWhenImpactedIdsChangeWithoutCountChange() {
        String first = StoreSanctionFingerprint.impactDigest(
                "case-1", 10L, 3L, 7L, "shape",
                List.of("reservation:101"), List.of("waiting:201"),
                List.of("pickup:301"), List.of("payment:pay-1"));
        String second = StoreSanctionFingerprint.impactDigest(
                "case-1", 10L, 3L, 7L, "shape",
                List.of("reservation:102"), List.of("waiting:202"),
                List.of("pickup:302"), List.of("payment:pay-2"));

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void verifyRejectsWhenCurrentImpactIdsChangedAfterPreview() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        StoreAdministrationService stores = mock(StoreAdministrationService.class);
        StoreReservationImpactQueryService reservations = mock(StoreReservationImpactQueryService.class);
        StoreWaitingImpactQueryService waiting = mock(StoreWaitingImpactQueryService.class);
        StorePickupImpactQueryService pickups = mock(StorePickupImpactQueryService.class);
        StorePaymentImpactQueryService payments = mock(StorePaymentImpactQueryService.class);
        StoreSanctionImpactPreviewRepository previews = mock(StoreSanctionImpactPreviewRepository.class);
        SanctionShape shape = new SanctionShape(SanctionType.FEATURE_RESTRICTION,
                Set.of(RestrictedFeature.RESERVATION), null, null);
        String shapeFingerprint = StoreSanctionFingerprint.shape(
                shape.type(), shape.restrictedFeatures(), null, null);
        String storedDigest = StoreSanctionFingerprint.impactDigest(
                "case-1", 10L, 3L, 7L, shapeFingerprint,
                List.of("101"), List.of("201"), List.of("301"), List.of("pay-1"));
        StoreSanctionImpactPreview preview = StoreSanctionImpactPreview.create(
                "case-1", 10L, 3L, 7L, 1, 1, 1, 1,
                shapeFingerprint, storedDigest, now.plusSeconds(600));
        given(previews.findById(1L)).willReturn(Optional.of(preview));
        given(stores.inspect(10L)).willReturn(new EnforcementResult(10L, 7L,
                OperationStatus.OPEN, true, true, true, true, true, Set.of()));
        given(reservations.inspect(10L, now)).willReturn(new StoreReservationImpact(10L, 1, Set.of(102L)));
        given(waiting.inspect(10L)).willReturn(new StoreWaitingImpact(10L, 1, Set.of(202L)));
        given(pickups.inspect(10L, now)).willReturn(new StorePickupImpact(10L, 1, Set.of(302L)));
        given(payments.inspectReservationDeposits(Set.of(102L)))
                .willReturn(new StorePaymentImpact(1, 1, Set.of("pay-2")));
        StoreSanctionImpactService service = new StoreSanctionImpactService(
                mock(StoreSanctionCaseService.class), stores, reservations, waiting, pickups,
                payments, previews, Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.verify(10L, "case-1",
                new ImpactConfirmation(1L, storedDigest, 3L, 7L), shape))
                .isInstanceOf(ServiceException.class);
    }
}
