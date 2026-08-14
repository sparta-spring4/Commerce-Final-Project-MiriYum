package com.miriyum.domain.reservation.waiting.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WaitingTeamTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-12T03:00:00Z");
    private static final Instant CALLED_AT = Instant.parse("2026-08-12T03:01:00Z");
    private static final Instant ARRIVAL_DEADLINE = Instant.parse("2026-08-12T03:11:00Z");

    @Test
    @DisplayName("공개 웨이팅 상태는 예약 전환 완료를 포함한 승인된 아홉 값만 제공한다")
    void exposesOnlyApprovedWaitingStatuses() {
        // when & then
        assertThat(Arrays.stream(WaitingTeamStatus.values()).map(Enum::name)).containsExactly(
                "WAITING",
                "CALLED",
                "ARRIVED",
                "CHECKED_IN",
                "CANCELLED",
                "NO_SHOW",
                "CLOSED_BY_STORE",
                "RESERVATION_CONVERTING",
                "RESERVATION_CONVERTED"
        );
    }

    @Test
    @DisplayName("새 웨이팅 팀은 매장 영업일 FIFO 순번으로 대기 상태에서 시작한다")
    void createsWaitingTeamAtVersionZero() {
        // when
        WaitingTeam team = newTeam();

        // then
        assertThat(team.getStoreId()).isEqualTo(10L);
        assertThat(team.getConsumerAccountId()).isEqualTo(20L);
        assertThat(team.getBusinessDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(team.getPartySize()).isEqualTo(2);
        assertThat(team.getSource()).isEqualTo(WaitingSource.REMOTE);
        assertThat(team.getQueueSequence()).isEqualTo(7L);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.WAITING);
        assertThat(team.getVersion()).isZero();
        assertThat(team.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("매장 영업일 순번 행은 현재 순번을 반환한 뒤 단조 증가한다")
    void allocatesMonotonicStoreBusinessDateSequence() {
        // given
        WaitingQueueSequence sequence = WaitingQueueSequence.create(
                10L,
                LocalDate.of(2026, 8, 12)
        );

        // when & then
        assertThat(sequence.allocate()).isEqualTo(1L);
        assertThat(sequence.allocate()).isEqualTo(2L);
        assertThat(sequence.getNextSequence()).isEqualTo(3L);
    }

    @Test
    @DisplayName("대기 팀 호출은 호출 시각과 10분 도착 제한을 고정하고 버전을 한 번 증가시킨다")
    void callsWaitingTeamOnce() {
        // given
        WaitingTeam team = newTeam();

        // when
        team.call(0L, CALLED_AT);

        // then
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(team.getCalledAt()).isEqualTo(CALLED_AT);
        assertThat(team.getArrivalDeadline()).isEqualTo(ARRIVAL_DEADLINE);
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("호출된 팀은 도착 제한 시각까지 도착 상태로 전이할 수 있다")
    void arrivesAtInclusiveDeadline() {
        // given
        WaitingTeam team = calledTeam();

        // when
        team.arrive(1L, ARRIVAL_DEADLINE);

        // then
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.ARRIVED);
        assertThat(team.getArrivedAt()).isEqualTo(ARRIVAL_DEADLINE);
        assertThat(team.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("도착 제한 시각을 지난 호출 팀은 도착 상태로 전이할 수 없다")
    void rejectsArrivalAfterDeadline() {
        // given
        WaitingTeam team = calledTeam();

        // when & then
        assertThatThrownBy(() -> team.arrive(1L, ARRIVAL_DEADLINE.plusNanos(1)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("도착 확인된 팀은 체크인 완료 상태로 전이하고 버전을 한 번 증가시킨다")
    void checksInArrivedTeam() {
        // given
        WaitingTeam team = arrivedTeam();
        Instant checkedInAt = Instant.parse("2026-08-12T03:03:00Z");

        // when
        team.checkIn(2L, checkedInAt);

        // then
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CHECKED_IN);
        assertThat(team.getCheckedInAt()).isEqualTo(checkedInAt);
        assertThat(team.getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("대기 중인 팀은 사용자 취소 상태로 전이할 수 있다")
    void cancelsWaitingTeam() {
        // given
        WaitingTeam team = newTeam();
        Instant cancelledAt = Instant.parse("2026-08-12T03:01:00Z");

        // when
        team.cancel(0L, cancelledAt);

        // then
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(team.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("호출 중인 팀은 사용자 취소 상태로 전이할 수 있다")
    void cancelsCalledTeam() {
        // given
        WaitingTeam team = calledTeam();
        Instant cancelledAt = Instant.parse("2026-08-12T03:02:00Z");

        // when
        team.cancel(1L, cancelledAt);

        // then
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(team.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(team.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("도착 확인된 팀은 사용자 취소 상태로 전이할 수 있다")
    void cancelsArrivedTeam() {
        // given
        WaitingTeam team = arrivedTeam();
        Instant cancelledAt = Instant.parse("2026-08-12T03:04:00Z");

        // when
        team.cancel(2L, cancelledAt);

        // then
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(team.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(team.getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("호출 이전 시각으로 취소 시각을 되돌릴 수 없다")
    void rejectsCalledCancellationTimestampRollback() {
        WaitingTeam team = calledTeam();

        assertThatThrownBy(() -> team.cancel(1L, CALLED_AT.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("도착 이전 시각으로 취소 시각을 되돌릴 수 없다")
    void rejectsArrivedCancellationTimestampRollback() {
        WaitingTeam team = arrivedTeam();

        assertThatThrownBy(() -> team.cancel(2L, Instant.parse("2026-08-12T03:01:59.999999999Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.ARRIVED);
        assertThat(team.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("호출 팀은 도착 제한 시각부터 미응답 종료할 수 있다")
    void marksCalledTeamNoShowAtDeadline() {
        // given
        WaitingTeam team = calledTeam();

        // when
        team.markNoShow(1L, ARRIVAL_DEADLINE);

        // then
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.NO_SHOW);
        assertThat(team.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("도착 제한 시각 전에는 호출 팀을 미응답 종료할 수 없다")
    void rejectsNoShowBeforeDeadline() {
        // given
        WaitingTeam team = calledTeam();

        // when & then
        assertThatThrownBy(() -> team.markNoShow(1L, ARRIVAL_DEADLINE.minusNanos(1)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("매장 종료는 모든 활성 상태의 팀을 매장 종료 상태로 전이한다")
    void closesEveryActiveStateByStore() {
        // given
        WaitingTeam waiting = newTeam();
        WaitingTeam called = calledTeam();
        WaitingTeam arrived = arrivedTeam();

        // when
        waiting.closeByStore(0L, Instant.parse("2026-08-12T03:05:00Z"));
        called.closeByStore(1L, Instant.parse("2026-08-12T03:05:00Z"));
        arrived.closeByStore(2L, Instant.parse("2026-08-12T03:05:00Z"));

        // then
        assertThat(waiting.getStatus()).isEqualTo(WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(waiting.getVersion()).isEqualTo(1L);
        assertThat(called.getStatus()).isEqualTo(WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(called.getVersion()).isEqualTo(2L);
        assertThat(arrived.getStatus()).isEqualTo(WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(arrived.getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("호출 이전 시각으로 매장 종료 시각을 되돌릴 수 없다")
    void rejectsCalledStoreClosureTimestampRollback() {
        WaitingTeam team = calledTeam();

        assertThatThrownBy(() -> team.closeByStore(1L, CALLED_AT.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("도착 이전 시각으로 매장 종료 시각을 되돌릴 수 없다")
    void rejectsArrivedStoreClosureTimestampRollback() {
        WaitingTeam team = arrivedTeam();

        assertThatThrownBy(() -> team.closeByStore(
                2L,
                Instant.parse("2026-08-12T03:01:59.999999999Z")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.ARRIVED);
        assertThat(team.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("오래된 expectedVersion 명령은 상태와 버전을 변경하지 않는다")
    void rejectsStaleVersionWithoutMutation() {
        // given
        WaitingTeam team = calledTeam();

        // when & then
        assertThatThrownBy(() -> team.arrive(0L, Instant.parse("2026-08-12T03:02:00Z")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(team.getArrivedAt()).isNull();
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("현재 상태에서 허용되지 않은 전이는 상태와 버전을 변경하지 않는다")
    void rejectsInvalidTransitionWithoutMutation() {
        // given
        WaitingTeam team = calledTeam();

        // when & then
        assertThatThrownBy(() -> team.checkIn(1L, Instant.parse("2026-08-12T03:02:00Z")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("모든 도달 가능한 종결 상태는 모든 공개 전이를 거부한다")
    void keepsTerminalStatesImmutableAcrossEveryPublicTransition() {
        // given
        WaitingTeam checkedIn = arrivedTeam();
        checkedIn.checkIn(2L, Instant.parse("2026-08-12T03:03:00Z"));
        WaitingTeam cancelled = newTeam();
        cancelled.cancel(0L, Instant.parse("2026-08-12T03:01:00Z"));
        WaitingTeam noShow = calledTeam();
        noShow.markNoShow(1L, ARRIVAL_DEADLINE);
        WaitingTeam closed = newTeam();
        closed.closeByStore(0L, Instant.parse("2026-08-12T03:01:00Z"));

        // when & then
        assertTerminalRejectsEveryTransition(checkedIn, 3L);
        assertTerminalRejectsEveryTransition(cancelled, 1L);
        assertTerminalRejectsEveryTransition(noShow, 2L);
        assertTerminalRejectsEveryTransition(closed, 1L);
    }

    @Test
    @DisplayName("웨이팅 원장은 예약 전환 시작 실패 완료 명령을 공개한다")
    void exposesReservationConversionLifecycleMethods() {
        // when
        Set<String> transitionMethods = Arrays.stream(WaitingTeam.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getReturnType() == void.class)
                .map(Method::getName)
                .collect(Collectors.toSet());

        // then
        assertThat(Arrays.stream(WaitingTeamStatus.values()).map(Enum::name))
                .contains("RESERVATION_CONVERTING", "RESERVATION_CONVERTED");
        assertThat(transitionMethods).contains(
                "call",
                "arrive",
                "checkIn",
                "cancel",
                "markNoShow",
                "closeByStore",
                "beginReservationConversion",
                "failReservationConversion",
                "completeReservationConversion"
        );
    }

    @Test
    @DisplayName("대기 팀은 결제 식별자와 시각을 기록하고 예약 전환을 시작한다")
    void beginsReservationConversionFromWaiting() {
        WaitingTeam team = newTeam();
        Instant convertingAt = CREATED_AT.plusSeconds(30);

        team.beginReservationConversion(0L, "123456789", convertingAt);

        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(team.getReservationConvertingAt()).isEqualTo(convertingAt);
        assertThat(team.getWaitingPaymentId()).isEqualTo("123456789");
        assertThat(team.getReservationReferenceId()).isNull();
        assertThat(team.getReservationConvertedAt()).isNull();
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("예약 전환 시작은 현재 버전과 대기 상태를 요구한다")
    void rejectsStaleOrRepeatedReservationConversionStart() {
        WaitingTeam team = newTeam();

        assertThatThrownBy(() -> team.beginReservationConversion(1L, "123456789", CREATED_AT))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT);

        beginReservationConversion(team, 0L, "123456789", CREATED_AT);
        assertThatThrownBy(() -> beginReservationConversion(
                team,
                1L,
                "123456789",
                CREATED_AT.plusSeconds(1)
        )).isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
    }

    @Test
    @DisplayName("예약 전환 시작은 양의 Payment 공개 ID와 생성 이후 시각을 요구한다")
    void validatesReservationConversionStartArguments() {
        WaitingTeam team = newTeam();

        assertThatThrownBy(() -> beginReservationConversion(team, 0L, "0", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> beginReservationConversion(
                team,
                0L,
                "12345678901234567890",
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> beginReservationConversion(
                team,
                0L,
                "123456789",
                CREATED_AT.minusNanos(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.WAITING);
        assertThat(team.getVersion()).isZero();
    }

    @Test
    @DisplayName("일치하는 결제 전환 실패는 시도 필드를 지우고 같은 팀을 대기로 되돌린다")
    void failsReservationConversionBackToWaiting() {
        WaitingTeam team = convertingTeam("123456789", CREATED_AT.plusSeconds(10));

        team.failReservationConversion(1L, "123456789", CREATED_AT.plusSeconds(20));

        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.WAITING);
        assertThat(team.getReservationConvertingAt()).isNull();
        assertThat(team.getWaitingPaymentId()).isNull();
        assertThat(team.getReservationReferenceId()).isNull();
        assertThat(team.getReservationConvertedAt()).isNull();
        assertThat(team.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("예약 전환 실패는 현재 버전 결제 식별자와 전환 이후 시각을 요구한다")
    void validatesReservationConversionFailure() {
        WaitingTeam team = convertingTeam("123456789", CREATED_AT.plusSeconds(10));

        assertThatThrownBy(() -> failReservationConversion(
                team,
                0L,
                "123456789",
                CREATED_AT.plusSeconds(20)
        )).isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT);
        assertThatThrownBy(() -> failReservationConversion(
                team,
                1L,
                "987654321",
                CREATED_AT.plusSeconds(20)
        )).isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        assertThatThrownBy(() -> failReservationConversion(
                team,
                1L,
                "123456789",
                CREATED_AT.plusSeconds(9)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("일치하는 결제 전환 완료는 최종 예약 참조와 완료 시각을 기록한다")
    void completesReservationConversion() {
        WaitingTeam team = convertingTeam("123456789", CREATED_AT.plusSeconds(10));
        Instant convertedAt = CREATED_AT.plusSeconds(20);

        team.completeReservationConversion(1L, "123456789", 987L, convertedAt);

        assertThat(team.getStatus().name()).isEqualTo("RESERVATION_CONVERTED");
        assertThat(team.getReservationConvertingAt())
                .isEqualTo(CREATED_AT.plusSeconds(10));
        assertThat(team.getWaitingPaymentId()).isEqualTo("123456789");
        assertThat(team.getReservationReferenceId()).isEqualTo(987L);
        assertThat(team.getReservationConvertedAt()).isEqualTo(convertedAt);
        assertThat(team.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("예약 전환 완료는 일치하는 결제 양의 예약 ID와 전환 이후 시각을 요구한다")
    void validatesReservationConversionCompletion() {
        WaitingTeam team = convertingTeam("123456789", CREATED_AT.plusSeconds(10));

        assertThatThrownBy(() -> completeReservationConversion(
                team, 1L, "987654321", 987L, CREATED_AT.plusSeconds(20)
        )).isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        assertThatThrownBy(() -> completeReservationConversion(
                team, 1L, "123456789", 0L, CREATED_AT.plusSeconds(20)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> completeReservationConversion(
                team, 1L, "123456789", 987L, CREATED_AT.plusSeconds(9)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("전환 중 취소와 매장 종료는 결제와 전환 시각을 보존한다")
    void preservesAttemptIdentityWhenConversionIsTerminated() {
        WaitingTeam cancelled = convertingTeam("123456789", CREATED_AT.plusSeconds(10));
        WaitingTeam closed = convertingTeam("987654321", CREATED_AT.plusSeconds(10));

        cancelled.cancel(1L, CREATED_AT.plusSeconds(20));
        closed.closeByStore(1L, CREATED_AT.plusSeconds(20));

        assertThat(cancelled.getStatus()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(cancelled.getReservationConvertingAt())
                .isEqualTo(CREATED_AT.plusSeconds(10));
        assertThat(cancelled.getWaitingPaymentId()).isEqualTo("123456789");
        assertThat(cancelled.getReservationReferenceId()).isNull();
        assertThat(cancelled.getReservationConvertedAt()).isNull();
        assertThat(closed.getStatus()).isEqualTo(WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(closed.getReservationConvertingAt())
                .isEqualTo(CREATED_AT.plusSeconds(10));
        assertThat(closed.getWaitingPaymentId()).isEqualTo("987654321");
        assertThat(closed.getReservationReferenceId()).isNull();
        assertThat(closed.getReservationConvertedAt()).isNull();
    }

    @Test
    @DisplayName("전환 시작 이전 시각으로 취소나 매장 종료 시각을 되돌릴 수 없다")
    void rejectsConversionTerminationTimestampRollback() {
        WaitingTeam cancelled = convertingTeam("123456789", CREATED_AT.plusSeconds(10));
        WaitingTeam closed = convertingTeam("987654321", CREATED_AT.plusSeconds(10));

        assertThatThrownBy(() -> cancelled.cancel(1L, CREATED_AT.plusSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> closed.closeByStore(1L, CREATED_AT.plusSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("V42 이전 예약 전환 snapshot은 취소와 매장 종료에서 생성 시각으로 fallback한다")
    void terminatesLegacyReservationConvertingSnapshotWithoutAttemptIdentity() {
        WaitingTeam cancelled = legacyReservationConvertingTeam();
        WaitingTeam closed = legacyReservationConvertingTeam();
        Instant terminatedAt = CREATED_AT.plusSeconds(10);

        cancelled.cancel(0L, terminatedAt);
        closed.closeByStore(0L, terminatedAt);

        assertThat(cancelled.getStatus()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isEqualTo(terminatedAt);
        assertThat(cancelled.getReservationConvertingAt()).isNull();
        assertThat(cancelled.getWaitingPaymentId()).isNull();
        assertThat(closed.getStatus()).isEqualTo(WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(closed.getClosedByStoreAt()).isEqualTo(terminatedAt);
        assertThat(closed.getReservationConvertingAt()).isNull();
        assertThat(closed.getWaitingPaymentId()).isNull();
    }

    @Test
    @DisplayName("예약 전환 중인 팀은 call arrive check-in 공개 명령을 모두 거절한다")
    void rejectsCallArriveAndCheckInFromReservationConverting() {
        WaitingTeam team = reservationConvertingTeam();

        assertThatThrownBy(() -> team.call(1L, CALLED_AT))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        assertThatThrownBy(() -> team.arrive(1L, CALLED_AT))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        assertThatThrownBy(() -> team.checkIn(1L, CALLED_AT))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(team.getVersion()).isEqualTo(1L);
    }

    private static WaitingTeam newTeam() {
        return WaitingTeam.create(
                10L,
                20L,
                LocalDate.of(2026, 8, 12),
                2,
                WaitingSource.REMOTE,
                7L,
                CREATED_AT
        );
    }

    private static WaitingTeam calledTeam() {
        WaitingTeam team = newTeam();
        team.call(0L, CALLED_AT);
        return team;
    }

    private static WaitingTeam arrivedTeam() {
        WaitingTeam team = calledTeam();
        team.arrive(1L, Instant.parse("2026-08-12T03:02:00Z"));
        return team;
    }

    private static WaitingTeam reservationConvertingTeam() {
        return convertingTeam("123456789", CREATED_AT);
    }

    private static WaitingTeam legacyReservationConvertingTeam() {
        WaitingTeam team = newTeam();
        try {
            var status = WaitingTeam.class.getDeclaredField("status");
            status.setAccessible(true);
            status.set(team, WaitingTeamStatus.RESERVATION_CONVERTING);
            return team;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static WaitingTeam convertingTeam(String paymentId, Instant convertingAt) {
        WaitingTeam team = newTeam();
        beginReservationConversion(team, 0L, paymentId, convertingAt);
        return team;
    }

    private static void beginReservationConversion(
            WaitingTeam team,
            long expectedVersion,
            String paymentId,
            Instant occurredAt
    ) {
        team.beginReservationConversion(expectedVersion, paymentId, occurredAt);
    }

    private static void failReservationConversion(
            WaitingTeam team,
            long expectedVersion,
            String paymentId,
            Instant occurredAt
    ) {
        team.failReservationConversion(expectedVersion, paymentId, occurredAt);
    }

    private static void completeReservationConversion(
            WaitingTeam team,
            long expectedVersion,
            String paymentId,
            long finalReservationId,
            Instant occurredAt
    ) {
        team.completeReservationConversion(
                expectedVersion,
                paymentId,
                finalReservationId,
                occurredAt
        );
    }

    private static void assertTerminalRejectsEveryTransition(WaitingTeam team, long expectedVersion) {
        WaitingTeamStatus beforeStatus = team.getStatus();
        Instant occurredAt = Instant.parse("2026-08-12T03:12:00Z");
        List<BiConsumer<Long, Instant>> transitions = List.of(
                team::call,
                team::arrive,
                team::checkIn,
                team::cancel,
                team::markNoShow,
                team::closeByStore
        );

        for (BiConsumer<Long, Instant> transition : transitions) {
            assertThatThrownBy(() -> transition.accept(expectedVersion, occurredAt))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getErrorCode())
                    .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
            assertThat(team.getStatus()).isEqualTo(beforeStatus);
            assertThat(team.getVersion()).isEqualTo(expectedVersion);
        }
    }

}
