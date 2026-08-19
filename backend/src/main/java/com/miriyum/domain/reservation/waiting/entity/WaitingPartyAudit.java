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

/** 계정 연락처나 위치 원문 없이 성공한 일행 변경의 최소 근거를 기록한다. */
@Entity
@Table(name = "waiting_party_audits", uniqueConstraints = @UniqueConstraint(
        name = "uk_waiting_party_audits_command", columnNames = "command_id"))
public class WaitingPartyAudit {

    public enum EventType {
        INVITATION_ISSUED, INVITATION_REVOKED, MEMBER_JOINED, MEMBER_DEPARTED,
        MEMBER_REMOVED, REPRESENTATIVE_TRANSFER_PROPOSED,
        REPRESENTATIVE_TRANSFER_ACCEPTED, REPRESENTATIVE_TRANSFER_REJECTED,
        REPRESENTATIVE_TRANSFER_REVOKED, REPRESENTATIVE_TRANSFER_EXPIRED
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_party_audit_id")
    private Long id;
    @Column(name = "waiting_team_id", nullable = false)
    private Long waitingTeamId;
    @Column(name = "actor_consumer_account_id", nullable = false)
    private Long actorConsumerAccountId;
    @Column(name = "subject_membership_id")
    private Long subjectMembershipId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 48)
    private EventType eventType;
    @Column(name = "before_team_version", nullable = false)
    private long beforeTeamVersion;
    @Column(name = "after_team_version", nullable = false)
    private long afterTeamVersion;
    @Column(name = "reason", nullable = false, length = 255)
    private String reason;
    @Column(name = "command_id", nullable = false, length = 36)
    private String commandId;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected WaitingPartyAudit() { }

    public static WaitingPartyAudit record(long teamId, long actorAccountId,
            Long subjectMembershipId, EventType eventType, long beforeVersion,
            long afterVersion, String reason, String commandId, Instant occurredAt) {
        WaitingPartyAudit value = new WaitingPartyAudit();
        value.waitingTeamId = positive(teamId, "teamId");
        value.actorConsumerAccountId = positive(actorAccountId, "actorAccountId");
        if (subjectMembershipId != null) value.subjectMembershipId = positive(subjectMembershipId, "subjectMembershipId");
        value.eventType = nonNull(eventType, "eventType");
        if (beforeVersion < 0 || afterVersion < beforeVersion || afterVersion > beforeVersion + 1) {
            throw new IllegalArgumentException("invalid team version transition");
        }
        value.beforeTeamVersion = beforeVersion;
        value.afterTeamVersion = afterVersion;
        value.reason = text(reason, 255, "reason");
        value.commandId = text(commandId, 36, "commandId");
        value.occurredAt = nonNull(occurredAt, "occurredAt");
        return value;
    }

    private static long positive(long value, String name) { if (value <= 0) throw new IllegalArgumentException(name + " must be positive"); return value; }
    private static String text(String value, int max, String name) { if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(name + " invalid"); return value.trim(); }
    private static <T> T nonNull(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " must not be null"); return value; }
}
