package com.miriyum.domain.platformoperator.entity;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "platform_operator_auth_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformOperatorAuthEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "platform_operator_auth_event_id")
    private Long id;

    @Column(name = "platform_operator_account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private PlatformOperatorAuthEventType eventType;

    @Column(name = "authority_version", nullable = false)
    private long authorityVersion;

    @Column(name = "session_version", nullable = false)
    private long sessionVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlatformOperatorAuthEventOutcome outcome;

    @Column(name = "event_key", nullable = false, unique = true, length = 100)
    private String eventKey;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static PlatformOperatorAuthEvent record(
            Long accountId,
            PlatformOperatorAuthEventType eventType,
            long authorityVersion,
            long sessionVersion,
            PlatformOperatorAuthEventOutcome outcome,
            String eventKey,
            Instant occurredAt) {
        PlatformOperatorAuthEvent event = new PlatformOperatorAuthEvent();
        event.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        event.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        event.authorityVersion = authorityVersion;
        event.sessionVersion = sessionVersion;
        event.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        event.eventKey = requireText(eventKey);
        event.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        return event;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("eventKey must not be blank");
        return value;
    }
}
