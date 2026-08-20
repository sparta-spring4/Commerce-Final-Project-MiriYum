package com.miriyum.domain.platformoperator.entity.membersupport;

import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_support_audits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberSupportAudit extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_support_audit_id")
    private Long id;
    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;
    @Column(name = "operator_id", nullable = false)
    private long operatorId;
    @Column(name = "roles_snapshot", nullable = false, columnDefinition = "JSON")
    private String rolesSnapshot;
    @Column(name = "permissions_snapshot", nullable = false, columnDefinition = "JSON")
    private String permissionsSnapshot;
    @Column(name = "authority_version", nullable = false)
    private long authorityVersion;
    @Column(name = "case_type", nullable = false, length = 50)
    private String caseType;
    @Column(name = "case_id", nullable = false, length = 100)
    private String caseId;
    @Column(name = "case_version", nullable = false)
    private long caseVersion;
    @Column(name = "purpose", nullable = false, length = 50)
    private String purpose;
    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;
    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;
    @Column(name = "approval_fingerprint", nullable = false, length = 64)
    private String approvalFingerprint;
    @Column(name = "decision_code", nullable = false, length = 40)
    private String decisionCode;
    @Column(name = "policy_version", length = 50)
    private String policyVersion;
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
    @Column(name = "retention_until")
    private LocalDateTime retentionUntil;

    public static MemberSupportAudit record(AdminAuditContext context, String decisionCode,
                                            String policyVersion, LocalDateTime occurredAt,
                                            LocalDateTime retentionUntil) {
        MemberSupportAudit audit = new MemberSupportAudit();
        audit.correlationId = context.correlationId();
        audit.operatorId = context.operatorId();
        audit.rolesSnapshot = json(context.roles());
        audit.permissionsSnapshot = json(context.permissions());
        audit.authorityVersion = context.authorityVersion();
        audit.caseType = context.caseType().name();
        audit.caseId = context.caseId();
        audit.caseVersion = context.caseVersion();
        audit.purpose = context.purpose().name();
        audit.targetType = context.targetType().name();
        audit.targetId = context.targetId();
        audit.approvalFingerprint = context.approvalFingerprint();
        audit.decisionCode = decisionCode;
        audit.policyVersion = policyVersion;
        audit.occurredAt = occurredAt;
        audit.retentionUntil = retentionUntil;
        return audit;
    }

    private static String json(Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted()
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Override
    public String toString() {
        return "MemberSupportAudit[id=" + id + ", correlationId=" + correlationId
                + ", operatorId=" + operatorId + ", caseId=" + caseId
                + ", decisionCode=" + decisionCode + "]";
    }
}
