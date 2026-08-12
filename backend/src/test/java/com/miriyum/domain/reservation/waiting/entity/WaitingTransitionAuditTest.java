package com.miriyum.domain.reservation.waiting.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WaitingTransitionAuditTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-12T03:00:00Z");

    @Test
    @DisplayName("생성 감사는 버전 -1에서 aggregate 생성 버전 0으로 전이한다")
    void recordsCreationFromMinusOneToZero() {
        WaitingTransitionAudit audit = WaitingTransitionAudit.record(
                11L,
                WaitingActorType.CONSUMER,
                22L,
                null,
                WaitingTeamStatus.WAITING,
                -1L,
                "CREATE",
                "creation-command",
                CREATED_AT,
                CREATED_AT
        );

        assertThat(audit)
                .extracting("expectedVersion", "resultVersion")
                .containsExactly(-1L, 0L);
    }

    @Test
    @DisplayName("일반 전이 감사는 현재 버전에서 정확히 하나 증가한다")
    void recordsTransitionWithSingleVersionIncrement() {
        WaitingTransitionAudit audit = WaitingTransitionAudit.record(
                11L,
                WaitingActorType.STORE_OPERATOR,
                33L,
                WaitingTeamStatus.WAITING,
                WaitingTeamStatus.CALLED,
                0L,
                "CALL",
                "call-command",
                CREATED_AT.plusSeconds(60),
                CREATED_AT
        );

        assertThat(audit)
                .extracting("expectedVersion", "resultVersion")
                .containsExactly(0L, 1L);
    }

    @Test
    @DisplayName("생성이 아닌 전이는 음수 expectedVersion을 허용하지 않는다")
    void rejectsNegativeTransitionVersion() {
        assertThatThrownBy(() -> WaitingTransitionAudit.record(
                11L,
                WaitingActorType.STORE_OPERATOR,
                33L,
                WaitingTeamStatus.WAITING,
                WaitingTeamStatus.CALLED,
                -1L,
                "CALL",
                "invalid-version-command",
                CREATED_AT.plusSeconds(60),
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SYSTEM actor ID는 null 또는 양수만 허용한다")
    void rejectsNegativeSystemActorId() {
        assertThatThrownBy(() -> WaitingTransitionAudit.record(
                11L,
                WaitingActorType.SYSTEM,
                -1L,
                WaitingTeamStatus.CALLED,
                WaitingTeamStatus.NO_SHOW,
                1L,
                "NO_SHOW",
                "negative-system-command",
                CREATED_AT.plusSeconds(600),
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("감사 발생 시각은 저장 생성 시각보다 이를 수 없다")
    void rejectsOccurredAtBeforeCreatedAt() {
        assertThatThrownBy(() -> WaitingTransitionAudit.record(
                11L,
                WaitingActorType.SYSTEM,
                null,
                WaitingTeamStatus.CALLED,
                WaitingTeamStatus.NO_SHOW,
                1L,
                "NO_SHOW",
                "rollback-time-command",
                CREATED_AT.minusNanos(1),
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
