package com.miriyum.domain.reservation.waiting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** 알림 payload와 분리된 최소 공개 웨이팅 상태 사건이다. */
@Entity
@Table(
        name = "waiting_status_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_waiting_status_events_team_sequence",
                columnNames = {"waiting_team_id", "event_sequence"}
        ))
public class WaitingStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_status_event_id")
    private Long id;

    @Column(name = "waiting_team_id", nullable = false)
    private Long waitingTeamId;

    @Column(name = "event_sequence", nullable = false)
    private long eventSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "public_status", nullable = false, length = 32)
    private WaitingTeamStatus publicStatus;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_state", nullable = false, length = 16)
    private WaitingStatusEventPublicationState publicationState;

    protected WaitingStatusEvent() {
    }

    private WaitingStatusEvent(
            long waitingTeamId,
            long eventSequence,
            WaitingTeamStatus publicStatus,
            Instant occurredAt
    ) {
        if (waitingTeamId <= 0 || eventSequence <= 0) {
            throw new IllegalArgumentException("team and event sequence must be positive");
        }
        if (publicStatus == null || occurredAt == null) {
            throw new IllegalArgumentException("status event fields must not be null");
        }
        this.waitingTeamId = waitingTeamId;
        this.eventSequence = eventSequence;
        this.publicStatus = publicStatus;
        this.occurredAt = occurredAt;
        this.publicationState = WaitingStatusEventPublicationState.PENDING;
    }

    /** 성공한 aggregate 전이의 공개 상태 사건을 발행 대기로 기록한다. */
    public static WaitingStatusEvent pending(
            long waitingTeamId,
            long eventSequence,
            WaitingTeamStatus publicStatus,
            Instant occurredAt
    ) {
        return new WaitingStatusEvent(waitingTeamId, eventSequence, publicStatus, occurredAt);
    }
}
