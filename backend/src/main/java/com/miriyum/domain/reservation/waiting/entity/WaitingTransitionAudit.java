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

/** 성공한 웨이팅 상태 전이를 명령 단위 append-only 사건으로 보존한다. */
@Entity
@Table(
        name = "waiting_transition_audits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_waiting_transition_audits_command",
                columnNames = "command_id"
        ))
public class WaitingTransitionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_transition_audit_id")
    private Long id;

    @Column(name = "waiting_team_id", nullable = false)
    private Long waitingTeamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 32)
    private WaitingActorType actorType;

    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_status", length = 32)
    private WaitingTeamStatus beforeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_status", nullable = false, length = 32)
    private WaitingTeamStatus afterStatus;

    @Column(name = "expected_version", nullable = false)
    private long expectedVersion;

    @Column(name = "result_version", nullable = false)
    private long resultVersion;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "command_id", nullable = false, length = 100)
    private String commandId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WaitingTransitionAudit() {
    }

    private WaitingTransitionAudit(
            long waitingTeamId,
            WaitingActorType actorType,
            Long actorId,
            WaitingTeamStatus beforeStatus,
            WaitingTeamStatus afterStatus,
            long expectedVersion,
            String reason,
            String commandId,
            Instant occurredAt,
            Instant createdAt
    ) {
        if (waitingTeamId <= 0) {
            throw new IllegalArgumentException("waitingTeamId must be positive");
        }
        if (actorType == null || afterStatus == null || occurredAt == null || createdAt == null) {
            throw new IllegalArgumentException("audit fields must not be null");
        }
        if (actorType == WaitingActorType.SYSTEM && actorId != null && actorId <= 0) {
            throw new IllegalArgumentException("system actorId must be positive when present");
        }
        if (actorType != WaitingActorType.SYSTEM && (actorId == null || actorId <= 0)) {
            throw new IllegalArgumentException("non-system actorId must be positive");
        }
        if (beforeStatus == afterStatus || (beforeStatus == null && afterStatus != WaitingTeamStatus.WAITING)) {
            throw new IllegalArgumentException("audit must describe an allowed state change");
        }
        if (beforeStatus == null && expectedVersion != -1L) {
            throw new IllegalArgumentException("creation audit expectedVersion must be -1");
        }
        if (beforeStatus != null && expectedVersion < 0L) {
            throw new IllegalArgumentException("transition expectedVersion must not be negative");
        }
        if (occurredAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("occurredAt must not be before createdAt");
        }
        this.waitingTeamId = waitingTeamId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.beforeStatus = beforeStatus;
        this.afterStatus = afterStatus;
        this.expectedVersion = expectedVersion;
        this.resultVersion = expectedVersion + 1;
        this.reason = requireText(reason, 255, "reason");
        this.commandId = requireText(commandId, 100, "commandId");
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    /** 커밋된 웨이팅 전이의 행위자와 명령 근거를 기록한다. */
    public static WaitingTransitionAudit record(
            long waitingTeamId,
            WaitingActorType actorType,
            Long actorId,
            WaitingTeamStatus beforeStatus,
            WaitingTeamStatus afterStatus,
            long expectedVersion,
            String reason,
            String commandId,
            Instant occurredAt,
            Instant createdAt
    ) {
        return new WaitingTransitionAudit(
                waitingTeamId,
                actorType,
                actorId,
                beforeStatus,
                afterStatus,
                expectedVersion,
                reason,
                commandId,
                occurredAt,
                createdAt
        );
    }

    private static String requireText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must contain 1 to " + maxLength + " characters");
        }
        return value.trim();
    }
}
