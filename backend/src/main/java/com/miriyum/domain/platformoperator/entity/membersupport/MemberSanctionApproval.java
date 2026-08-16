package com.miriyum.domain.platformoperator.entity.membersupport;

import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_sanction_approvals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberSanctionApproval extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_sanction_approval_id")
    private Long id;
    @Column(name = "member_sanction_id", nullable = false)
    private long memberSanctionId;
    @Column(name = "proposer_operator_id", nullable = false)
    private long proposerOperatorId;
    @Column(name = "approver_operator_id", nullable = false)
    private long approverOperatorId;
    @Column(name = "reason_code", nullable = false, length = 100)
    private String reasonCode;
    @Column(name = "approved_at", nullable = false)
    private LocalDateTime approvedAt;

    public static MemberSanctionApproval record(MemberSanction sanction, long approverId,
                                                String reasonCode, LocalDateTime now) {
        MemberSanctionApproval approval = new MemberSanctionApproval();
        approval.memberSanctionId = sanction.getId();
        approval.proposerOperatorId = sanction.getProposedByOperatorId();
        approval.approverOperatorId = approverId;
        approval.reasonCode = reasonCode;
        approval.approvedAt = now;
        return approval;
    }
}
