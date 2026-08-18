package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochService;
import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochSnapshot;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCheckInQrGrant;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.entity.ReservationNoShowReason;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldTerminationPresence;
import com.miriyum.domain.reservation.repository.ReservationCheckInAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationCheckInQrGrantRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositDispositionObligationRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationFulfillmentAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationNoShowAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReservationVisitServiceTest {

    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final Instant START_AT = Instant.parse("2026-08-16T01:00:00Z");
    private static final Instant REQUESTED_AT = START_AT.plusSeconds(60);
    private static final byte[] DIGEST = new byte[32];
    private static final ConsumerQrEpochSnapshot EPOCH = new ConsumerQrEpochSnapshot(
            11L, "v1." + "A".repeat(43)
    );

    @Mock private StoreService storeService;
    @Mock private IdempotencyExecutor idempotencyExecutor;
    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationCheckInQrGrantRepository grantRepository;
    @Mock private ConsumerQrEpochService epochService;
    @Mock private ReservationMenuHoldPort menuHoldPort;
    @Mock private ReservationFulfillmentAuditRepository fulfillmentAuditRepository;
    @Mock private ReservationCheckInAuditRepository checkInAuditRepository;
    @Mock private ReservationNoShowAuditRepository noShowAuditRepository;
    @Mock private ReservationDepositProcessRepository depositProcessRepository;
    @Mock private ReservationDepositDispositionObligationRepository
            dispositionObligationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("fresh QR scan은 current grant·epoch·시간을 검증하고 모든 종결 기록을 함께 만든다")
    void freshQrScanFulfillsAndConsumesGrant() {
        Reservation reservation = reservation();
        ReservationCheckInQrGrant grant = ReservationCheckInQrGrant.issue(
                77L, DIGEST, EPOCH, REQUESTED_AT.minusSeconds(10)
        );
        given(fulfillmentAuditRepository.saveAndFlush(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(checkInAuditRepository.save(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
        stubFreshExecutor();
        given(grantRepository.findReservationIdByTokenDigest(DIGEST)).willReturn(Optional.of(77L));
        given(reservationRepository.findByIdAndStoreIdForUpdate(77L, 22L))
                .willReturn(Optional.of(reservation));
        given(grantRepository.findByReservationIdForUpdate(77L)).willReturn(Optional.of(grant));
        given(menuHoldPort.lockForTermination(77L))
                .willReturn(ReservationMenuHoldTerminationPresence.HOLD_PRESENT);
        given(menuHoldPort.fulfill(
                77L,
                "reservation-qr-check-in:store-operator:33:" + KEY
        ))
                .willReturn(ReservationMenuHoldResult.fulfilled(77L));
        given(menuHoldPort.findSnapshots(77L)).willReturn(List.of());

        ReservationVisitCommandResult result = serviceAt(REQUESTED_AT).checkIn(
                33L, 22L, DIGEST, checkInCommand(), REQUESTED_AT,
                "reservation-qr-check-in:store-operator:33:" + KEY
        );

        assertThat(result.data().status()).isEqualTo("FULFILLED");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(grant.getConsumedAt()).isEqualTo(REQUESTED_AT);
        then(epochService).should().requireCurrent(11L, EPOCH);
        then(fulfillmentAuditRepository).should().saveAndFlush(any());
        then(checkInAuditRepository).should().save(any());
    }

    @Test
    @DisplayName("V2 QR 체크인은 전액 환불 처분 obligation을 응답과 함께 저장한다")
    void v2QrCheckInCreatesPendingFullRefundDisposition() {
        Reservation reservation = depositReservation();
        ReservationCheckInQrGrant grant = ReservationCheckInQrGrant.issue(
                77L, DIGEST, EPOCH, REQUESTED_AT.minusSeconds(10)
        );
        given(fulfillmentAuditRepository.saveAndFlush(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(checkInAuditRepository.save(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
        stubFreshExecutor();
        given(grantRepository.findReservationIdByTokenDigest(DIGEST)).willReturn(Optional.of(77L));
        given(reservationRepository.findByIdAndStoreIdForUpdate(77L, 22L))
                .willReturn(Optional.of(reservation));
        given(grantRepository.findByReservationIdForUpdate(77L)).willReturn(Optional.of(grant));
        given(menuHoldPort.lockForTermination(77L))
                .willReturn(ReservationMenuHoldTerminationPresence.NO_HOLD);
        stubDepositProcessLink();

        ReservationVisitCommandResult result = serviceAt(REQUESTED_AT).checkIn(
                33L, 22L, DIGEST, checkInCommand(), REQUESTED_AT,
                "reservation-qr-check-in:store-operator:33:" + KEY
        );

        assertThat(result.data().depositDisposition()).isNotNull();
        assertThat(result.data().depositDisposition().responsibilityCode())
                .isEqualTo("CONSUMER");
        assertThat(result.data().depositDisposition().targetRefundRateBasisPoints())
                .isEqualTo(10_000);
        assertThat(result.data().depositDisposition().status()).isEqualTo("PENDING");
        then(dispositionObligationRepository).should().saveAndFlush(any());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("approvedNoShowDispositionCases")
    @DisplayName("V2 노쇼는 승인된 reason별 목표 환불률만 obligation으로 저장한다")
    void v2NoShowCreatesApprovedDisposition(
            ReservationNoShowReason reason,
            String responsibilityCode,
            Integer targetRefundRateBasisPoints
    ) {
        Reservation reservation = depositReservation();
        stubFreshExecutor();
        given(reservationRepository.findByIdAndStoreIdForUpdate(77L, 22L))
                .willReturn(Optional.of(reservation));
        given(menuHoldPort.lockForTermination(77L))
                .willReturn(ReservationMenuHoldTerminationPresence.NO_HOLD);
        given(noShowAuditRepository.save(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
        if (responsibilityCode != null) {
            stubDepositProcessLink();
        }

        ReservationVisitCommandResult result = serviceAt(START_AT.plusSeconds(300)).markNoShow(
                33L,
                22L,
                77L,
                reason,
                noShowCommand(),
                START_AT.plusSeconds(300),
                "reservation-no-show:store-operator:33:" + KEY
        );

        if (responsibilityCode == null) {
            assertThat(result.data().depositDisposition()).isNull();
            then(depositProcessRepository).shouldHaveNoInteractions();
            then(dispositionObligationRepository).shouldHaveNoInteractions();
            return;
        }
        assertThat(result.data().depositDisposition()).isNotNull();
        assertThat(result.data().depositDisposition().responsibilityCode())
                .isEqualTo(responsibilityCode);
        assertThat(result.data().depositDisposition().targetRefundRateBasisPoints())
                .isEqualTo(targetRefundRateBasisPoints);
        assertThat(result.data().depositDisposition().status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("정확히 startAt+5분부터 QR은 거부되고 노쇼는 허용된다")
    void appliesSharedFiveMinuteBoundary() {
        Instant boundary = START_AT.plusSeconds(300);
        Reservation scanReservation = reservation();
        ReservationCheckInQrGrant grant = ReservationCheckInQrGrant.issue(
                77L, DIGEST, EPOCH, boundary.minusSeconds(20)
        );
        stubFreshExecutor();
        given(grantRepository.findReservationIdByTokenDigest(DIGEST)).willReturn(Optional.of(77L));
        given(reservationRepository.findByIdAndStoreIdForUpdate(77L, 22L))
                .willReturn(Optional.of(scanReservation));
        given(grantRepository.findByReservationIdForUpdate(77L)).willReturn(Optional.of(grant));

        assertThatThrownBy(() -> serviceAt(boundary).checkIn(
                33L, 22L, DIGEST, checkInCommand(), boundary,
                "reservation-qr-check-in:store-operator:33:" + KEY
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.OUTSIDE_CHECK_IN_WINDOW));

        Reservation noShowReservation = reservation();
        given(noShowAuditRepository.save(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(reservationRepository.findByIdAndStoreIdForUpdate(77L, 22L))
                .willReturn(Optional.of(noShowReservation));
        given(menuHoldPort.lockForTermination(77L))
                .willReturn(ReservationMenuHoldTerminationPresence.HOLD_PRESENT);
        given(menuHoldPort.forfeit(
                77L,
                "reservation-no-show:store-operator:33:" + KEY
        ))
                .willReturn(ReservationMenuHoldResult.forfeited(77L));
        given(menuHoldPort.findSnapshots(77L)).willReturn(List.of());

        ReservationVisitCommandResult result = serviceAt(boundary).markNoShow(
                33L, 22L, 77L, ReservationNoShowReason.UNCLEAR,
                noShowCommand(), boundary, "reservation-no-show:store-operator:33:" + KEY
        );

        assertThat(result.data().status()).isEqualTo("NO_SHOW");
        assertThat(noShowReservation.getNoShowAt()).isEqualTo(boundary);
        then(menuHoldPort).should().forfeit(
                77L,
                "reservation-no-show:store-operator:33:" + KEY
        );
    }

    @Test
    @DisplayName("성공 replay는 현재 매장 권한만 확인하고 QR·epoch·시간·상태를 재검증하지 않는다")
    void replayChecksOnlyCurrentStoreAuthority() {
        Reservation replayed = reservation();
        replayed.fulfill(REQUESTED_AT);
        given(idempotencyExecutor.execute(any(), any())).willReturn(new IdempotentOutcome(
                true, 200, "SUCCESS", "RESERVATION", "77",
                objectMapper.valueToTree(
                        com.miriyum.domain.reservation.dto.response.ReservationDetailResponse
                                .from(replayed, List.of())
                )
        ));

        ReservationVisitCommandResult result = serviceAt(START_AT.plusSeconds(1000)).checkIn(
                33L, 22L, DIGEST, checkInCommand(), START_AT.plusSeconds(1000),
                "reservation-qr-check-in:store-operator:33:" + KEY
        );

        assertThat(result.data().status()).isEqualTo("FULFILLED");
        then(storeService).should().requireManagementOwnership(33L, 22L);
        then(grantRepository).should(never()).findReservationIdByTokenDigest(any());
        then(epochService).shouldHaveNoInteractions();
    }

    private ReservationVisitService serviceAt(Instant now) {
        return new ReservationVisitService(
                storeService, idempotencyExecutor, reservationRepository, grantRepository,
                epochService, menuHoldPort, fulfillmentAuditRepository,
                checkInAuditRepository, noShowAuditRepository,
                depositProcessRepository, dispositionObligationRepository,
                Clock.fixed(now, ZoneOffset.UTC), objectMapper
        );
    }

    private void stubDepositProcessLink() {
        ReservationDepositProcessRepository.DepositProcessLink link =
                mock(ReservationDepositProcessRepository.DepositProcessLink.class);
        given(link.getProcessId()).willReturn(31L);
        given(link.getStatus()).willReturn(ReservationDepositProcessStatus.COMPLETED);
        given(link.getFinalReservationId()).willReturn(77L);
        given(link.getPaymentId()).willReturn("51");
        given(depositProcessRepository.findDepositProcessLinkByFinalReservationId(77L))
                .willReturn(Optional.of(link));
        given(dispositionObligationRepository.saveAndFlush(
                any(ReservationDepositDispositionObligation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private static Stream<Arguments> approvedNoShowDispositionCases() {
        return Stream.of(
                Arguments.of(ReservationNoShowReason.USER_CAUSE_CANDIDATE, "CONSUMER", 0),
                Arguments.of(
                        ReservationNoShowReason.STORE_CAUSE_CANDIDATE,
                        "STORE_RESPONSIBLE",
                        10_000),
                Arguments.of(
                        ReservationNoShowReason.PLATFORM_EXTERNAL_CAUSE_CANDIDATE,
                        "PLATFORM_RESPONSIBLE",
                        10_000),
                Arguments.of(ReservationNoShowReason.UNCLEAR, null, null)
        );
    }

    @SuppressWarnings("unchecked")
    private void stubFreshExecutor() {
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(
                    false,
                    result.httpStatus(),
                    result.responseCode(),
                    result.resourceType(),
                    result.resourceId(),
                    objectMapper.valueToTree(result.data())
            );
        });
    }

    private static IdempotencyCommand checkInCommand() {
        return new IdempotencyCommand("store-operator", 33L, "RESERVATION_QR_CHECK_IN",
                KEY, "a".repeat(64));
    }

    private static IdempotencyCommand noShowCommand() {
        return new IdempotencyCommand("store-operator", 33L, "RESERVATION_NO_SHOW",
                KEY, "b".repeat(64));
    }

    private static Reservation reservation() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                22L, 5L, 30, 60, 15
        );
        policy.activate(Instant.parse("2026-08-15T00:00:00Z"), "test");
        Reservation reservation = Reservation.confirm(
                11L, 22L, "미리윰",
                ReservationTimeSnapshot.calculate(
                        policy,
                        LocalDateTime.of(2026, 8, 16, 10, 0),
                        ZoneId.of("Asia/Seoul"), null
                ),
                PartyComposition.of(2, 0, 0),
                ReservationContactSnapshot.contactable("consumer:11:channel:primary"),
                3L, new ReservationCancellationPolicyVersion(1L),
                Instant.parse("2026-08-15T01:00:00Z")
        );
        ReflectionTestUtils.setField(reservation, "id", 77L);
        return reservation;
    }

    private static Reservation depositReservation() {
        Reservation reservation = reservation();
        ReflectionTestUtils.setField(reservation, "cancellationPolicyVersion", 2L);
        return reservation;
    }
}
