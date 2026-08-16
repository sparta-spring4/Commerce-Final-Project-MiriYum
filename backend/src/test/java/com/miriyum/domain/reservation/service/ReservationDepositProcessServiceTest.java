package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationDepositProcessServiceTest {

    private static final long HOLD_ID = 77L;
    private static final long RESERVATION_ID = 88L;
    private static final long CONSUMER_ID = 11L;
    private static final long STORE_ID = 22L;
    private static final long CAPACITY_POLICY_VERSION = 7L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 20);
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @Test
    void finalizationCopiesHeldCapacityWithoutMutatingOccupiedTotals() {
        ReservationHoldRepository holdRepository = mock(ReservationHoldRepository.class);
        ReservationHoldCapacityAllocationRepository holdAllocationRepository =
                mock(ReservationHoldCapacityAllocationRepository.class);
        ReservationCapacityBucketRepository bucketRepository =
                mock(ReservationCapacityBucketRepository.class);
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        ReservationCapacityAllocationRepository allocationRepository =
                mock(ReservationCapacityAllocationRepository.class);
        ReservationHoldTransitionPrimitive transitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationDepositFinalizationPrimitive primitive =
                new ReservationDepositFinalizationPrimitive(
                        holdRepository,
                        holdAllocationRepository,
                        bucketRepository,
                        reservationRepository,
                        allocationRepository,
                        transitionPrimitive);
        ReservationHold hold = activeHold();
        List<ReservationHoldCapacityAllocation> heldAllocations = List.of(
                ReservationHoldCapacityAllocation.allocate(
                        HOLD_ID, 101L, 3, CAPACITY_POLICY_VERSION),
                ReservationHoldCapacityAllocation.allocate(
                        HOLD_ID, 102L, 3, CAPACITY_POLICY_VERSION));
        ReservationCapacityBucket first = bucket(101L, LocalTime.of(12, 0),
                LocalTime.of(12, 30), 9, 2);
        ReservationCapacityBucket second = bucket(102L, LocalTime.of(12, 30),
                LocalTime.of(13, 0), 7, 1);
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(holdAllocationRepository
                .findAllByReservationHoldIdOrderByCapacityBucketIdAsc(HOLD_ID))
                .willReturn(heldAllocations);
        given(reservationRepository.saveAndFlush(any(Reservation.class)))
                .willAnswer(invocation -> {
                    Reservation saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", RESERVATION_ID);
                    return saved;
                });
        given(bucketRepository.findAllByIdInForUpdate(List.of(101L, 102L)))
                .willReturn(List.of(first, second));

        Reservation result = primitive.finalizeResources(
                new ReservationDepositFinalizationPrimitive.Command(
                        HOLD_ID,
                        "reservation-deposit-finalize:request-key",
                        "CONSUMER",
                        CONSUMER_ID,
                        NOW));

        assertThat(result.getId()).isEqualTo(RESERVATION_ID);
        assertThat(first.getOccupiedPeople()).isEqualTo(9);
        assertThat(first.getOccupiedTeams()).isEqualTo(2);
        assertThat(second.getOccupiedPeople()).isEqualTo(7);
        assertThat(second.getOccupiedTeams()).isEqualTo(1);
        ArgumentCaptor<ReservationHoldContracts.TransitionCommand> transition =
                ArgumentCaptor.forClass(ReservationHoldContracts.TransitionCommand.class);
        InOrder order = inOrder(
                holdRepository,
                holdAllocationRepository,
                reservationRepository,
                transitionPrimitive,
                bucketRepository,
                allocationRepository);
        order.verify(holdRepository).findByIdForUpdate(HOLD_ID);
        order.verify(holdAllocationRepository)
                .findAllByReservationHoldIdOrderByCapacityBucketIdAsc(HOLD_ID);
        order.verify(reservationRepository).saveAndFlush(any(Reservation.class));
        order.verify(transitionPrimitive).transition(transition.capture());
        order.verify(bucketRepository).findAllByIdInForUpdate(List.of(101L, 102L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReservationCapacityAllocation>> allocations =
                ArgumentCaptor.forClass(List.class);
        order.verify(allocationRepository).saveAll(allocations.capture());
        assertThat(transition.getValue())
                .isEqualTo(new ReservationHoldContracts.TransitionCommand(
                        HOLD_ID,
                        ReservationHoldStatus.CONFIRMED,
                        "reservation-deposit-finalize:request-key",
                        "CONSUMER",
                        CONSUMER_ID,
                        NOW,
                        RESERVATION_ID));
        assertThat(allocations.getValue())
                .extracting(
                        ReservationCapacityAllocation::getCapacityBucketId,
                        ReservationCapacityAllocation::getOccupiedPeople,
                        ReservationCapacityAllocation::getOccupiedTeams,
                        ReservationCapacityAllocation::getCapacityPolicyVersion)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, 3, 1, 7L),
                        org.assertj.core.groups.Tuple.tuple(102L, 3, 1, 7L));
    }

    private static ReservationHold activeHold() {
        ReservationTimePolicyVersion timePolicy = ReservationTimePolicyVersion.createDraft(
                STORE_ID, 5L, 30, 60, 0);
        timePolicy.activate(NOW.minusSeconds(1), "test");
        ReservationTimeSnapshot time = ReservationTimeSnapshot.calculate(
                timePolicy,
                LocalDateTime.of(SERVICE_DATE, LocalTime.NOON),
                ZoneId.of("Asia/Seoul"),
                null);
        ReservationHold hold = ReservationHold.active(
                CONSUMER_ID,
                STORE_ID,
                "미리윰 매장",
                time,
                PartyComposition.of(2, 1, 0),
                ReservationContactSnapshot.contactable("contact-ref"),
                CAPACITY_POLICY_VERSION,
                new ReservationCancellationPolicyVersion(1L),
                "reservation-deposit-create:key",
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(hold, "id", HOLD_ID);
        return hold;
    }

    private static ReservationCapacityBucket bucket(
            long id,
            LocalTime start,
            LocalTime end,
            int occupiedPeople,
            int occupiedTeams
    ) {
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                STORE_ID,
                SERVICE_DATE,
                start,
                end,
                20,
                10,
                occupiedPeople,
                occupiedTeams,
                1,
                10,
                true,
                CAPACITY_POLICY_VERSION);
        ReflectionTestUtils.setField(bucket, "id", id);
        return bucket;
    }
}
