package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochSnapshot;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCheckInAudit;
import com.miriyum.domain.reservation.entity.ReservationCheckInQrGrant;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCheckInAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationCheckInQrGrantRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationCheckInQrGrantServiceTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-16T01:00:00Z");
    private static final Instant OCCURRED_AT = REQUESTED_AT.plusSeconds(1);
    private static final ConsumerQrEpochSnapshot EPOCH = new ConsumerQrEpochSnapshot(
            11L, "v1." + "A".repeat(43)
    );

    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationCheckInQrGrantRepository grantRepository;
    @Mock private ReservationCheckInAuditRepository auditRepository;

    private ReservationCheckInQrGrantService service;

    @BeforeEach
    void setUp() {
        service = new ReservationCheckInQrGrantService(
                reservationRepository,
                grantRepository,
                auditRepository,
                Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("예약 잠금 아래 첫 current grant와 발급 감사를 함께 저장한다")
    void issuesFirstGrantUnderReservationLock() {
        Reservation reservation = reservation();
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(reservation));
        given(grantRepository.findByReservationIdForUpdate(77L)).willReturn(Optional.empty());
        given(grantRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(auditRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        ReservationCheckInQrGrantService.IssuedGrant issued = service.issue(
                11L, 77L, digest((byte) 1), EPOCH, REQUESTED_AT
        );

        assertThat(issued.reservationId()).isEqualTo(77L);
        assertThat(issued.tokenVersion()).isEqualTo(1L);
        assertThat(issued.issuedAt()).isEqualTo(REQUESTED_AT);
        assertThat(issued.expiresAt()).isEqualTo(REQUESTED_AT.plusSeconds(30));
        then(grantRepository).should().save(any(ReservationCheckInQrGrant.class));
        ArgumentCaptor<ReservationCheckInAudit> audit =
                ArgumentCaptor.forClass(ReservationCheckInAudit.class);
        then(auditRepository).should().save(audit.capture());
        assertThat(audit.getValue().getTokenVersion()).isEqualTo(1L);
        assertThat(audit.getValue().getOccurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    @DisplayName("기존 current 행을 새 digest·epoch·version으로 회전한다")
    void rotatesExistingGrant() {
        Reservation reservation = reservation();
        ReservationCheckInQrGrant current = ReservationCheckInQrGrant.issue(
                77L, digest((byte) 1), EPOCH, REQUESTED_AT.minusSeconds(10)
        );
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(reservation));
        given(grantRepository.findByReservationIdForUpdate(77L))
                .willReturn(Optional.of(current));
        given(grantRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(auditRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        ReservationCheckInQrGrantService.IssuedGrant issued = service.issue(
                11L, 77L, digest((byte) 2), EPOCH, REQUESTED_AT
        );

        assertThat(issued.tokenVersion()).isEqualTo(2L);
        assertThat(current.getTokenDigest()).containsExactly(digest((byte) 2));
    }

    @Test
    @DisplayName("타인 예약과 종결 예약에는 grant를 발급하지 않는다")
    void rejectsHiddenOrTerminalReservation() {
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(
                11L, 77L, digest((byte) 1), EPOCH, REQUESTED_AT
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));

        Reservation terminal = reservation();
        terminal.fulfill(REQUESTED_AT);
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(terminal));

        assertThatThrownBy(() -> service.issue(
                11L, 77L, digest((byte) 1), EPOCH, REQUESTED_AT
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION));
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
                        ZoneId.of("Asia/Seoul"),
                        null
                ),
                PartyComposition.of(2, 0, 0),
                ReservationContactSnapshot.contactable("consumer:11:channel:primary"),
                3L,
                new ReservationCancellationPolicyVersion(1L),
                Instant.parse("2026-08-15T01:00:00Z")
        );
        ReflectionTestUtils.setField(reservation, "id", 77L);
        return reservation;
    }

    private static byte[] digest(byte value) {
        byte[] digest = new byte[32];
        java.util.Arrays.fill(digest, value);
        return digest;
    }
}
