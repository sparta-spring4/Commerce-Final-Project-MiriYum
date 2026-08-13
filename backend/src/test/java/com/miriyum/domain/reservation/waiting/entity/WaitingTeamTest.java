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
    @DisplayName("공개 웨이팅 상태는 승인된 여덟 값만 제공한다")
    void exposesOnlyApprovedWaitingStatuses() {
        // when & then
        assertThat(WaitingTeamStatus.values()).containsExactly(
                WaitingTeamStatus.WAITING,
                WaitingTeamStatus.CALLED,
                WaitingTeamStatus.ARRIVED,
                WaitingTeamStatus.CHECKED_IN,
                WaitingTeamStatus.CANCELLED,
                WaitingTeamStatus.NO_SHOW,
                WaitingTeamStatus.CLOSED_BY_STORE,
                WaitingTeamStatus.RESERVATION_CONVERTING
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
    @DisplayName("예약 전환 중 상태는 공개 상태지만 이 원장의 진입 명령으로 노출하지 않는다")
    void doesNotExposeReservationConvertingEntryMethod() {
        // when
        Set<String> transitionMethods = Arrays.stream(WaitingTeam.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getReturnType() == void.class)
                .filter(WaitingTeamTest::acceptsVersionAndOccurrence)
                .map(Method::getName)
                .collect(Collectors.toSet());

        // then
        assertThat(WaitingTeamStatus.values())
                .contains(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(transitionMethods).containsExactlyInAnyOrder(
                "call",
                "arrive",
                "checkIn",
                "cancel",
                "markNoShow",
                "closeByStore"
        );
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

    private static boolean acceptsVersionAndOccurrence(Method method) {
        return Arrays.equals(method.getParameterTypes(), new Class<?>[]{long.class, Instant.class});
    }
}
