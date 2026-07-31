package com.miriyum.domain.store.schedule.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.store.schedule.model.ConflictCheckStatus;
import com.miriyum.domain.store.schedule.model.ScheduleActorType;
import com.miriyum.domain.store.schedule.model.ScheduleAuditAction;
import com.miriyum.domain.store.schedule.model.ScheduleAuditOutcome;
import com.miriyum.domain.store.schedule.model.ScheduleAuditRecord;
import com.miriyum.domain.store.schedule.model.ScheduleStream;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoreScheduleVersionTest {

    private static final Instant EFFECTIVE_AT =
            Instant.parse("2026-08-01T03:00:00Z");

    @Test
    @DisplayName("영업시간 초안은 예약 게시를 취소하면 내용이 유지된 초안으로 돌아간다")
    void scheduledOperatingDraftReturnsToDraftWhenCancelled() {
        OperatingScheduleVersion version =
                OperatingScheduleVersion.createDraft(
                        7L,
                        1L,
                        "Asia/Seoul",
                        List.of());
        version.schedule(EFFECTIVE_AT, "여름 영업시간");

        version.cancelPublication();

        assertThat(version.getStatus()).isEqualTo(ScheduleVersionStatus.DRAFT);
        assertThat(version.getEffectiveAt()).isNull();
        assertThat(version.getChangeReason()).isNull();
        assertThat(version.getTimeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(version.getEntries()).isEmpty();
    }

    @Test
    @DisplayName("활성 영업시간 버전은 게시 취소할 수 없다")
    void activeOperatingVersionCannotBeCancelled() {
        OperatingScheduleVersion version =
                OperatingScheduleVersion.createDraft(
                        7L,
                        1L,
                        "Asia/Seoul",
                        List.of());
        version.activate(EFFECTIVE_AT, "첫 게시");

        assertThatThrownBy(version::cancelPublication)
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.SCHEDULE_CONFLICT);
    }

    @Test
    @DisplayName("활성 예약 접수 버전은 종료 상태로만 전이한다")
    void activeReservationVersionCanBeRetired() {
        ReservationScheduleVersion version =
                ReservationScheduleVersion.createDraft(
                        7L,
                        1L,
                        21L,
                        "Asia/Seoul",
                        List.of());
        version.activate(EFFECTIVE_AT, "첫 게시");

        version.retire();

        assertThat(version.getStatus()).isEqualTo(ScheduleVersionStatus.RETIRED);
        assertThat(version.getActivatedAt()).isEqualTo(EFFECTIVE_AT);
        assertThat(version.getValidatedOperatingVersionId()).isEqualTo(21L);
    }

    @Test
    @DisplayName("예약 게시 활성화 실패는 예약 상태에서 실패 상태로 종결한다")
    void scheduledReservationVersionCanFailActivation() {
        ReservationScheduleVersion version =
                ReservationScheduleVersion.createDraft(
                        7L,
                        1L,
                        21L,
                        "Asia/Seoul",
                        List.of());
        version.schedule(EFFECTIVE_AT, "휴게시간 반영");

        version.failActivation();

        assertThat(version.getStatus())
                .isEqualTo(ScheduleVersionStatus.ACTIVATION_FAILED);
        assertThat(version.getActivatedAt()).isNull();
    }

    @Test
    @DisplayName("운영자 게시 감사는 행위자·사유를 남기고 충돌을 미평가로 구분한다")
    void operatorPublicationAuditDoesNotClaimZeroConflicts() {
        StoreScheduleAuditEvent event = StoreScheduleAuditEvent.recordOperator(
                7L,
                11L,
                new ScheduleAuditRecord(
                        ScheduleStream.OPERATING,
                        2L,
                        1L,
                        2L,
                        ScheduleAuditAction.IMMEDIATE_PUBLISHED,
                        ScheduleVersionStatus.DRAFT,
                        ScheduleVersionStatus.ACTIVE,
                        "Asia/Seoul",
                        EFFECTIVE_AT,
                        EFFECTIVE_AT,
                        EFFECTIVE_AT,
                        "여름 영업시간",
                        "550e8400-e29b-41d4-a716-446655440000",
                        ScheduleAuditOutcome.SUCCEEDED));

        assertThat(event.getActorType())
                .isEqualTo(ScheduleActorType.STORE_OPERATOR);
        assertThat(event.getActorId()).isEqualTo(11L);
        assertThat(event.getConflictCheckStatus())
                .isEqualTo(ConflictCheckStatus.NOT_EVALUATED);
        assertThat(event.getConflictCount()).isNull();
        assertThat(event.getChangeReason()).isEqualTo("여름 영업시간");
    }
}
