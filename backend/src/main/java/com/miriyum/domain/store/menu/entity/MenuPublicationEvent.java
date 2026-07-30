package com.miriyum.domain.store.menu.entity;

import com.miriyum.domain.store.menu.enums.MenuPublicationEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "menu_publication_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuPublicationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_publication_event_id")
    private Long id;

    @Column(name = "menu_id", nullable = false)
    private long menuId;

    @Column(name = "version_number")
    private Integer versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private MenuPublicationEventType eventType;

    @Column(name = "actor_operator_id")
    private Long actorOperatorId;

    @Column(name = "commanded_at", nullable = false)
    private Instant commandedAt;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public static MenuPublicationEvent record(
            long menuId,
            Integer versionNumber,
            MenuPublicationEventType eventType,
            Long actorOperatorId,
            Instant commandedAt,
            Instant effectiveAt,
            Instant confirmedAt
    ) {
        MenuPublicationEvent event = new MenuPublicationEvent();
        event.menuId = menuId;
        event.versionNumber = versionNumber;
        event.eventType = eventType;
        event.actorOperatorId = actorOperatorId;
        event.commandedAt = commandedAt;
        event.effectiveAt = effectiveAt;
        event.confirmedAt = confirmedAt;
        return event;
    }
}
