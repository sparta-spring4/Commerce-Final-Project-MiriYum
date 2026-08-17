package com.miriyum.domain.reservation.waiting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** 앞선 활성 팀이 최초 2팀 이하가 된 시점의 팀별 1회 비상태 사건이다. */
@Entity
@Table(
        name = "waiting_entry_imminent_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_waiting_entry_imminent_events_team",
                columnNames = "waiting_team_id"
        ))
public class WaitingEntryImminentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_entry_imminent_event_id")
    private Long id;

    @Column(name = "waiting_team_id", nullable = false)
    private Long waitingTeamId;

    @Column(name = "event_sequence", nullable = false)
    private long eventSequence;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected WaitingEntryImminentEvent() {
    }

    private WaitingEntryImminentEvent(
            long waitingTeamId,
            long eventSequence,
            Instant occurredAt
    ) {
        if (waitingTeamId <= 0 || eventSequence <= 0 || occurredAt == null) {
            throw new IllegalArgumentException("entry-imminent event fields must be valid");
        }
        this.waitingTeamId = waitingTeamId;
        this.eventSequence = eventSequence;
        this.occurredAt = occurredAt;
    }

    public static WaitingEntryImminentEvent record(
            long waitingTeamId,
            long eventSequence,
            Instant occurredAt
    ) {
        return new WaitingEntryImminentEvent(waitingTeamId, eventSequence, occurredAt);
    }

    public Long getId() {
        return id;
    }

    public Long getWaitingTeamId() {
        return waitingTeamId;
    }

    public long getEventSequence() {
        return eventSequence;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
