package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationTimePolicyVersionTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final String REASON = "운영 시간 정책 변경";

    @Test
    @DisplayName("매장별 예약 시간 정책 초안은 버전과 분 단위 duration을 보존한다")
    void createsDraftWithVersionedDurations() {
        // when
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                11L,
                3L,
                30,
                90,
                15
        );

        // then
        assertThat(policy.getStoreId()).isEqualTo(11L);
        assertThat(policy.getVersionNumber()).isEqualTo(3L);
        assertThat(policy.getSlotIntervalMinutes()).isEqualTo(30);
        assertThat(policy.getServiceDurationMinutes()).isEqualTo(90);
        assertThat(policy.getTurnoverDurationMinutes()).isEqualTo(15);
        assertThat(policy.getStatus()).isEqualTo(ReservationTimePolicyStatus.DRAFT);
        assertThat(policy.getEffectiveAt()).isNull();
        assertThat(policy.getActivatedAt()).isNull();
    }

    @Test
    @DisplayName("시간 정책 duration 범위와 점유 합계 상한을 벗어나면 거부한다")
    void rejectsInvalidDurationBounds() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimePolicyVersion.createDraft(11L, 1L, 0, 90, 15));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimePolicyVersion.createDraft(11L, 1L, 1441, 90, 15));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimePolicyVersion.createDraft(11L, 1L, 30, 0, 15));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimePolicyVersion.createDraft(11L, 1L, 30, 1441, 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimePolicyVersion.createDraft(11L, 1L, 30, 90, -1));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimePolicyVersion.createDraft(11L, 1L, 30, 90, 1441));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimePolicyVersion.createDraft(11L, 1L, 30, 1440, 1));
    }

    @Test
    @DisplayName("시간 정책의 매장 ID와 버전은 양수여야 한다")
    void rejectsNonPositiveIdentity() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimePolicyVersion.createDraft(0L, 1L, 30, 90, 15));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimePolicyVersion.createDraft(11L, 0L, 30, 90, 15));
    }

    @Test
    @DisplayName("초안을 미래 효력 시각으로 게시 예약하고 효력 전에는 다시 초안으로 철회한다")
    void schedulesAndCancelsFuturePublication() {
        // given
        ReservationTimePolicyVersion policy = policy();
        Instant effectiveAt = NOW.plusSeconds(3600);

        // when
        policy.schedule(effectiveAt, NOW, REASON);

        // then
        assertThat(policy.getStatus()).isEqualTo(ReservationTimePolicyStatus.SCHEDULED);
        assertThat(policy.getEffectiveAt()).isEqualTo(effectiveAt);
        assertThat(policy.getActivatedAt()).isNull();
        assertThat(policy.getPublicationRequestedAt()).isEqualTo(NOW);
        assertThat(policy.getChangeReason()).isEqualTo(REASON);

        // when
        policy.cancelPublication(effectiveAt.minusNanos(1));

        // then
        assertThat(policy.getStatus()).isEqualTo(ReservationTimePolicyStatus.DRAFT);
        assertThat(policy.getEffectiveAt()).isNull();
        assertThat(policy.getActivatedAt()).isNull();
    }

    @Test
    @DisplayName("초안은 즉시 활성화하고 활성 정책은 퇴역시킨다")
    void activatesDraftImmediatelyAndRetiresActivePolicy() {
        // given
        ReservationTimePolicyVersion policy = policy();

        // when
        policy.activate(NOW, REASON);

        // then
        assertThat(policy.getStatus()).isEqualTo(ReservationTimePolicyStatus.ACTIVE);
        assertThat(policy.getEffectiveAt()).isEqualTo(NOW);
        assertThat(policy.getActivatedAt()).isEqualTo(NOW);
        assertThat(policy.getPublicationRequestedAt()).isEqualTo(NOW);
        assertThat(policy.getChangeReason()).isEqualTo(REASON);

        // when
        policy.retire();

        // then
        assertThat(policy.getStatus()).isEqualTo(ReservationTimePolicyStatus.RETIRED);
    }

    @Test
    @DisplayName("게시 예약 정책은 효력 시각부터 활성화할 수 있다")
    void activatesScheduledPolicyAtEffectiveBoundary() {
        // given
        ReservationTimePolicyVersion policy = policy();
        Instant effectiveAt = NOW.plusSeconds(3600);
        policy.schedule(effectiveAt, NOW, REASON);

        // when
        policy.activate(effectiveAt);

        // then
        assertThat(policy.getStatus()).isEqualTo(ReservationTimePolicyStatus.ACTIVE);
        assertThat(policy.getEffectiveAt()).isEqualTo(effectiveAt);
        assertThat(policy.getActivatedAt()).isEqualTo(effectiveAt);
    }

    @Test
    @DisplayName("게시 예약 정책의 활성화 실패를 별도 종결 상태로 기록한다")
    void marksScheduledActivationAsFailed() {
        // given
        ReservationTimePolicyVersion policy = policy();
        policy.schedule(NOW.plusSeconds(3600), NOW, REASON);

        // when
        policy.failActivation();

        // then
        assertThat(policy.getStatus())
                .isEqualTo(ReservationTimePolicyStatus.ACTIVATION_FAILED);
        assertThat(policy.getActivatedAt()).isNull();
    }

    @Test
    @DisplayName("과거나 현재 시각으로 게시 예약하거나 효력 전에 활성화할 수 없다")
    void rejectsInvalidEffectivityBoundaries() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                policy().schedule(NOW, NOW, REASON));
        assertThatIllegalArgumentException().isThrownBy(() ->
                policy().schedule(NOW.minusSeconds(1), NOW, REASON));

        ReservationTimePolicyVersion scheduled = policy();
        scheduled.schedule(NOW.plusSeconds(3600), NOW, REASON);
        assertThatIllegalArgumentException().isThrownBy(() ->
                scheduled.activate(NOW.plusSeconds(3599)));
    }

    @Test
    @DisplayName("게시 예약은 효력 시각 경계부터 철회할 수 없다")
    void rejectsPublicationCancellationAtOrAfterEffectiveTime() {
        Instant effectiveAt = NOW.plusSeconds(3600);
        ReservationTimePolicyVersion atBoundary = policy();
        atBoundary.schedule(effectiveAt, NOW, REASON);
        ReservationTimePolicyVersion afterBoundary = policy();
        afterBoundary.schedule(effectiveAt, NOW, REASON);

        assertThatIllegalArgumentException().isThrownBy(() ->
                atBoundary.cancelPublication(effectiveAt));
        assertThatIllegalArgumentException().isThrownBy(() ->
                afterBoundary.cancelPublication(effectiveAt.plusNanos(1)));
    }

    @Test
    @DisplayName("승인되지 않은 정책 상태 전이는 Reservation 오류로 거부한다")
    void rejectsInvalidLifecycleTransition() {
        ReservationTimePolicyVersion policy = policy();

        ServiceException exception = catchThrowableOfType(
                ServiceException.class,
                policy::retire
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("즉시·예약 게시에는 비어 있지 않은 변경 사유가 필요하다")
    void requiresPublicationReason() {
        ReservationTimePolicyVersion missingScheduledReason = policy();
        assertThatIllegalArgumentException().isThrownBy(() ->
                missingScheduledReason.schedule(NOW.plusSeconds(3600), NOW, null));
        assertThat(missingScheduledReason.getStatus())
                .isEqualTo(ReservationTimePolicyStatus.DRAFT);
        assertThat(missingScheduledReason.getEffectiveAt()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() ->
                policy().schedule(NOW.plusSeconds(3600), NOW, "  "));
        assertThatIllegalArgumentException().isThrownBy(() ->
                policy().activate(NOW, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                policy().activate(NOW, "  "));
    }

    private static ReservationTimePolicyVersion policy() {
        return ReservationTimePolicyVersion.createDraft(11L, 1L, 30, 90, 15);
    }
}
