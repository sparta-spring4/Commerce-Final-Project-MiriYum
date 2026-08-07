package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCancellationAuditTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-08T10:00:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T10:00:01Z");
    private static final String COMMAND_ID =
            "reservation-cancel:store-operator:33:550e8400-e29b-41d4-a716-446655440000";

    @Test
    @DisplayName("소비자 성공 취소 감사는 선택 사유를 보존한다")
    void recordsExactSuccessfulConsumerCancellation() {
        ReservationCancellationAudit audit = ReservationCancellationAudit.recordSuccess(
                77L,
                ReservationCancellationActorType.CONSUMER,
                11L,
                null,
                REQUESTED_AT,
                OCCURRED_AT,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
                1L,
                7L,
                "reservation-cancel:consumer:11:550e8400-e29b-41d4-a716-446655440000"
        );

        assertThat(audit.getReservationId()).isEqualTo(77L);
        assertThat(audit.getActorType()).isEqualTo(ReservationCancellationActorType.CONSUMER);
        assertThat(audit.getActorId()).isEqualTo(11L);
        assertThat(audit.getCancellationReason()).isNull();
        assertThat(audit.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(audit.getOccurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(audit.getBeforeStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(audit.getAfterStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(audit.getCancellationPolicyVersion()).isEqualTo(1L);
        assertThat(audit.getCapacityPolicyVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("운영자 성공 취소 감사는 전달된 값을 그대로 보존한다")
    void recordsExactSuccessfulOperatorCancellation() {
        ReservationCancellationAudit audit = validOperatorAudit("영업 종료");

        assertThat(audit.getReservationId()).isEqualTo(77L);
        assertThat(audit.getActorType()).isEqualTo(ReservationCancellationActorType.STORE_OPERATOR);
        assertThat(audit.getCancellationReason()).isEqualTo("영업 종료");
        assertThat(audit.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(audit.getOccurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    @DisplayName("운영자 사유는 공백 한 글자를 임의로 trim 검증하지 않고 보존한다")
    void acceptsWhitespaceAsAnOperatorReasonWithoutInventingTrimValidation() {
        ReservationCancellationAudit audit = validOperatorAudit(" ");

        assertThat(audit.getCancellationReason()).isEqualTo(" ");
    }

    @Test
    @DisplayName("양수가 아닌 식별자와 정책 버전은 성공 감사에 사용할 수 없다")
    void rejectsNonPositiveIdsAndVersions() {
        assertThatThrownBy(() -> recordSuccess(0L, 33L, 1L, 7L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordSuccess(77L, 0L, 1L, 7L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordSuccess(77L, 33L, 0L, 7L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordSuccess(77L, 33L, 1L, 0L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("운영자 성공 취소에는 사유가 필요하다")
    void rejectsMissingOperatorReason() {
        assertThatThrownBy(() -> validOperatorAudit(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 문자열과 501자 사유는 성공 감사에 사용할 수 없다")
    void rejectsEmptyOrTooLongReason() {
        assertThatThrownBy(() -> validOperatorAudit(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validOperatorAudit("a".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("성공 시각은 요청 시각보다 이를 수 없다")
    void rejectsReversedTimestamps() {
        assertThatThrownBy(() -> ReservationCancellationAudit.recordSuccess(
                77L,
                ReservationCancellationActorType.STORE_OPERATOR,
                33L,
                "영업 종료",
                OCCURRED_AT,
                REQUESTED_AT,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
                1L,
                7L,
                COMMAND_ID
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("성공 감사는 CONFIRMED에서 CANCELLED로의 전이만 기록한다")
    void rejectsAnyTransitionOtherThanConfirmedToCancelled() {
        assertThatThrownBy(() -> ReservationCancellationAudit.recordSuccess(
                77L,
                ReservationCancellationActorType.STORE_OPERATOR,
                33L,
                "영업 종료",
                REQUESTED_AT,
                OCCURRED_AT,
                ReservationStatus.CANCELLED,
                ReservationStatus.CANCELLED,
                1L,
                7L,
                COMMAND_ID
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("비어 있거나 100자를 넘는 command ID는 성공 감사에 사용할 수 없다")
    void rejectsBlankOrTooLongCommandId() {
        assertThatThrownBy(() -> recordSuccess(77L, 33L, 1L, 7L, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recordSuccess(77L, 33L, 1L, 7L, "a".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Long 최대값 actor ID의 정확히 90자 correlation을 허용한다")
    void acceptsExactNinetyCharacterLongMaxValueCorrelation() {
        String commandId = "reservation-cancel:store-operator:" + Long.MAX_VALUE + ":" + "a".repeat(36);

        ReservationCancellationAudit audit = recordSuccess(
                77L,
                Long.MAX_VALUE,
                1L,
                7L,
                commandId
        );

        assertThat(commandId).hasSize(90);
        assertThat(audit.getCommandId()).isEqualTo(commandId);
    }

    private ReservationCancellationAudit validOperatorAudit(String reason) {
        return recordSuccess(77L, 33L, 1L, 7L, COMMAND_ID, reason);
    }

    private ReservationCancellationAudit recordSuccess(
            long reservationId,
            long actorId,
            long cancellationPolicyVersion,
            long capacityPolicyVersion,
            String commandId
    ) {
        return recordSuccess(
                reservationId,
                actorId,
                cancellationPolicyVersion,
                capacityPolicyVersion,
                commandId,
                "영업 종료"
        );
    }

    private ReservationCancellationAudit recordSuccess(
            long reservationId,
            long actorId,
            long cancellationPolicyVersion,
            long capacityPolicyVersion,
            String commandId,
            String reason
    ) {
        return ReservationCancellationAudit.recordSuccess(
                reservationId,
                ReservationCancellationActorType.STORE_OPERATOR,
                actorId,
                reason,
                REQUESTED_AT,
                OCCURRED_AT,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
                cancellationPolicyVersion,
                capacityPolicyVersion,
                commandId
        );
    }
}
