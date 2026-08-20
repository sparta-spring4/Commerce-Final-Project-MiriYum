package com.miriyum.domain.menuhold.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "menu_hold_transition_audits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuHoldTransitionAudit {

    public enum EventType {
        BASELINE,
        CREATED,
        TRANSITION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_hold_transition_audit_id")
    private Long id;

    @Transient
    private MenuHold menuHold;

    @Column(name = "menu_hold_id", nullable = false, updatable = false)
    private Long menuHoldId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private long storeId;

    @Column(name = "reservation_id", updatable = false)
    private Long reservationId;

    @Column(name = "reservation_hold_id", updatable = false)
    private Long reservationHoldId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 16)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_status", updatable = false, length = 32)
    private MenuHoldStatus beforeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_status", nullable = false, updatable = false, length = 32)
    private MenuHoldStatus afterStatus;

    @Column(name = "result_version", nullable = false, updatable = false)
    private long resultVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "hold_created_at", nullable = false, updatable = false)
    private Instant holdCreatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "item_snapshots", nullable = false, updatable = false,
            columnDefinition = "json")
    private List<ItemSnapshot> itemSnapshots;

    public record ItemSnapshot(long menuId, String displayName, int quantity) {
        public ItemSnapshot {
            if (menuId <= 0 || displayName == null || displayName.isBlank() || quantity <= 0) {
                throw new IllegalArgumentException("invalid menu hold item snapshot");
            }
        }
    }

    public static MenuHoldTransitionAudit created(MenuHold hold, Instant occurredAt) {
        return of(hold, EventType.CREATED, null, hold.getStatus(), 0L, occurredAt);
    }

    public static MenuHoldTransitionAudit transition(
            MenuHold hold,
            MenuHoldStatus beforeStatus,
            Instant occurredAt
    ) {
        if (beforeStatus == null || beforeStatus == hold.getStatus()) {
            throw new IllegalArgumentException("transition must change menu hold status");
        }
        return of(
                hold,
                EventType.TRANSITION,
                beforeStatus,
                hold.getStatus(),
                Math.addExact(hold.getStatusVersion(), 1L),
                occurredAt);
    }

    private static MenuHoldTransitionAudit of(
            MenuHold hold,
            EventType eventType,
            MenuHoldStatus beforeStatus,
            MenuHoldStatus afterStatus,
            long resultVersion,
            Instant occurredAt
    ) {
        if (hold == null || eventType == null || afterStatus == null || resultVersion < 0
                || occurredAt == null) {
            throw new IllegalArgumentException("invalid menu hold transition audit");
        }
        MenuHoldTransitionAudit audit = new MenuHoldTransitionAudit();
        audit.menuHold = hold;
        audit.menuHoldId = hold.getId();
        audit.storeId = hold.getStoreId();
        audit.reservationId = hold.getReservationId();
        audit.reservationHoldId = hold.getReservationHoldId();
        audit.eventType = eventType;
        audit.beforeStatus = beforeStatus;
        audit.afterStatus = afterStatus;
        audit.resultVersion = resultVersion;
        audit.occurredAt = occurredAt;
        audit.holdCreatedAt = hold.getCreatedAt() == null
                ? occurredAt
                : hold.getCreatedAt().toInstant(ZoneOffset.UTC);
        audit.itemSnapshots = hold.getItems().stream()
                .map(item -> new ItemSnapshot(
                        item.getMenuId(), item.getMenuNameSnapshot(), item.getQuantity()))
                .toList();
        return audit;
    }
}
