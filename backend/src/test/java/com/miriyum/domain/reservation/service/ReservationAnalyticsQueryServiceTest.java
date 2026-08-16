package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.reservation.dto.contract.ReservationAnalyticsSnapshot;
import com.miriyum.domain.reservation.repository.ReservationCancellationAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationFulfillmentAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationAnalyticsQueryServiceTest {

    private static final long STORE_ID = 17L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 16);
    private static final Instant AS_OF = Instant.parse("2026-08-16T09:00:00Z");

    @Mock ReservationRepository reservationRepository;
    @Mock ReservationCapacityBucketRepository bucketRepository;
    @Mock ReservationCapacityAllocationRepository allocationRepository;
    @Mock ReservationCancellationAuditRepository cancellationAuditRepository;
    @Mock ReservationFulfillmentAuditRepository fulfillmentAuditRepository;

    @Mock ReservationRepository.ReservationAnalyticsLifecycle lifecycle;
    @Mock ReservationCapacityBucketRepository.ReservationCapacityOfferAnalytics offers;
    @Mock ReservationCapacityAllocationRepository.ReservationCapacityUsageAnalytics usage;
    @Mock ReservationCancellationAuditRepository.ReservationCancellationAnalytics cancellations;
    @Mock ReservationFulfillmentAuditRepository.ReservationFulfillmentAnalytics fulfillments;

    private ReservationAnalyticsQueryService service;

    @BeforeEach
    void setUp() {
        service = new ReservationAnalyticsQueryService(
                reservationRepository,
                bucketRepository,
                allocationRepository,
                cancellationAuditRepository,
                fulfillmentAuditRepository);
    }

    @Test
    void aggregatesDistinctConfirmedLifecycleTeamsAndExcludesFailedHolds() {
        given(reservationRepository.aggregateDashboardLifecycle(
                STORE_ID, BUSINESS_DATE, AS_OF)).willReturn(lifecycle);
        given(bucketRepository.aggregateDashboardOffers(
                STORE_ID, BUSINESS_DATE, AS_OF)).willReturn(offers);
        given(allocationRepository.aggregateDashboardUsage(
                STORE_ID, BUSINESS_DATE, AS_OF)).willReturn(usage);
        given(cancellationAuditRepository.aggregateDashboardCancellations(
                STORE_ID, BUSINESS_DATE, AS_OF)).willReturn(cancellations);
        given(fulfillmentAuditRepository.aggregateDashboardFulfillments(
                STORE_ID, BUSINESS_DATE, AS_OF)).willReturn(fulfillments);

        given(lifecycle.getTodayReservationTeams()).willReturn(12L);
        given(lifecycle.getCancelledTeams()).willReturn(2L);
        given(lifecycle.getEverConfirmedTeams()).willReturn(14L);
        given(lifecycle.getMaxReservationId()).willReturn(101L);
        given(lifecycle.getMaxCapacityPolicyVersion()).willReturn(7L);
        given(lifecycle.getDataThroughEpochMicros())
                .willReturn(epochMicros(AS_OF.minusSeconds(30)));

        given(usage.getReservedPeopleUnits()).willReturn(48L);
        given(usage.getReservedTeamUnits()).willReturn(12L);
        given(usage.getMaxAllocationId()).willReturn(201L);
        given(offers.getOfferedPeopleUnits()).willReturn(80L);
        given(offers.getOfferedTeamUnits()).willReturn(20L);
        given(offers.getPolicyVersion()).willReturn(7L);
        given(offers.getMaxBucketId()).willReturn(301L);
        given(offers.getDataThroughEpochMicros())
                .willReturn(epochMicros(AS_OF.minusSeconds(15)));
        given(cancellations.getMaxAuditId()).willReturn(401L);
        given(cancellations.getDataThroughEpochMicros())
                .willReturn(epochMicros(AS_OF.minusSeconds(20)));
        given(fulfillments.getMaxAuditId()).willReturn(501L);
        given(fulfillments.getDataThroughEpochMicros())
                .willReturn(epochMicros(AS_OF.minusSeconds(10)));

        ReservationAnalyticsSnapshot result = service.getDashboardSnapshot(
                STORE_ID, BUSINESS_DATE, AS_OF);

        assertThat(result.todayReservationTeams()).isEqualTo(12);
        assertThat(result.reservedPeopleUnits()).isEqualTo(48);
        assertThat(result.offeredPeopleUnits()).isEqualTo(80);
        assertThat(result.reservedTeamUnits()).isEqualTo(12);
        assertThat(result.offeredTeamUnits()).isEqualTo(20);
        assertThat(result.cancelledTeams()).isEqualTo(2);
        assertThat(result.everConfirmedTeams()).isEqualTo(14);
        assertThat(result.asOf()).isEqualTo(AS_OF);
        assertThat(result.dataThrough()).isEqualTo(AS_OF.minusSeconds(10));
        assertThat(result.inputCheckpoint()).hasSize(64);
        assertThat(result.sourceVersion()).isEqualTo(501L);
        assertThat(result.corrected()).isFalse();
    }

    private static long epochMicros(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }
}
