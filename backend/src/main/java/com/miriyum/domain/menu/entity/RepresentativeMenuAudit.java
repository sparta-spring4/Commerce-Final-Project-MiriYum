package com.miriyum.domain.menu.entity;

import com.miriyum.domain.menu.enums.RepresentativeMenuAuditActorType;
import com.miriyum.domain.menu.enums.RepresentativeMenuAuditEventType;
import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
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
@Table(name = "representative_menu_audits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepresentativeMenuAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "representative_menu_audit_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(name = "before_version", nullable = false)
    private long beforeVersion;

    @Column(name = "after_version", nullable = false)
    private long afterVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_status", nullable = false, length = 30)
    private RepresentativeMenuSettingStatus beforeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_status", nullable = false, length = 30)
    private RepresentativeMenuSettingStatus afterStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private RepresentativeMenuAuditActorType actorType;

    @Column(name = "actor_operator_id")
    private Long actorOperatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private RepresentativeMenuAuditEventType eventType;

    @Column(name = "trigger_menu_id")
    private Long triggerMenuId;

    @Column(name = "ordered_menu_ids_json", nullable = false, columnDefinition = "json")
    private String orderedMenuIdsJson;

    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static RepresentativeMenuAudit create(
            long storeId,
            long beforeVersion,
            long afterVersion,
            RepresentativeMenuSettingStatus beforeStatus,
            RepresentativeMenuSettingStatus afterStatus,
            RepresentativeMenuAuditActorType actorType,
            Long actorOperatorId,
            RepresentativeMenuAuditEventType eventType,
            Long triggerMenuId,
            String orderedMenuIdsJson,
            String requestId,
            Instant createdAt
    ) {
        RepresentativeMenuAudit audit = new RepresentativeMenuAudit();
        audit.storeId = storeId;
        audit.beforeVersion = beforeVersion;
        audit.afterVersion = afterVersion;
        audit.beforeStatus = beforeStatus;
        audit.afterStatus = afterStatus;
        audit.actorType = actorType;
        audit.actorOperatorId = actorOperatorId;
        audit.eventType = eventType;
        audit.triggerMenuId = triggerMenuId;
        audit.orderedMenuIdsJson = orderedMenuIdsJson;
        audit.requestId = requestId;
        audit.createdAt = createdAt;
        return audit;
    }
}
