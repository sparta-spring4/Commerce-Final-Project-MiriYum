package com.miriyum.domain.store.menu.entity;

import com.miriyum.domain.store.menu.enums.MenuPublicationEventType;
import com.miriyum.domain.store.menu.enums.MenuAuditActorType;
import com.miriyum.domain.store.menu.enums.MenuAuditOutcome;
import com.miriyum.domain.store.menu.enums.MenuImpactCheckStatus;
import com.miriyum.domain.store.menu.enums.MenuRecoveryResult;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.model.MenuAuditRecord;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private MenuAuditActorType actorType;

    @Column(name = "actor_operator_id")
    private Long actorOperatorId;

    @Column(name = "commanded_at", nullable = false)
    private Instant commandedAt;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private MenuAuditOutcome outcome;

    @Column(name = "previous_version_number")
    private Integer previousVersionNumber;

    @Column(name = "new_version_number")
    private Integer newVersionNumber;

    @Column(name = "changed_fields", nullable = false, length = 1000)
    private String changedFields;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_visibility", length = 20)
    private MenuVisibility previousVisibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_visibility", length = 20)
    private MenuVisibility newVisibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_selling_status", length = 20)
    private MenuSellingStatus previousSellingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_selling_status", length = 20)
    private MenuSellingStatus newSellingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "impact_check_status", nullable = false, length = 20)
    private MenuImpactCheckStatus impactCheckStatus;

    @Column(name = "impact_count")
    private Integer impactCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_result", nullable = false, length = 20)
    private MenuRecoveryResult recoveryResult;

    public static MenuPublicationEvent record(MenuAuditRecord record) {
        MenuPublicationEvent event = new MenuPublicationEvent();
        event.menuId = record.menuId();
        event.versionNumber = record.versionNumber();
        event.eventType = record.eventType();
        event.actorType = record.actorType();
        event.actorOperatorId = record.actorOperatorId();
        event.commandedAt = record.commandedAt();
        event.effectiveAt = record.effectiveAt();
        event.confirmedAt = record.confirmedAt();
        event.requestId = record.requestId();
        event.outcome = record.outcome();
        event.previousVersionNumber = record.previousVersionNumber();
        event.newVersionNumber = record.newVersionNumber();
        event.changedFields = record.changedFields();
        event.changeReason = record.changeReason();
        event.previousVisibility = record.previousVisibility();
        event.newVisibility = record.newVisibility();
        event.previousSellingStatus = record.previousSellingStatus();
        event.newSellingStatus = record.newSellingStatus();
        event.impactCheckStatus = record.impactCheckStatus();
        event.impactCount = record.impactCount();
        event.recoveryResult = record.recoveryResult();
        return event;
    }
}
