package com.miriyum.domain.platformoperator.adminstore.entity;
import jakarta.persistence.*; import java.time.Instant; import lombok.*;
@Entity @Table(name="store_sanction_approvals",uniqueConstraints=@UniqueConstraint(name="uk_store_sanction_approver",columnNames={"store_sanction_id","approver_id"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class StoreSanctionApproval {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="store_sanction_approval_id") private Long id;
 @Column(name="store_sanction_id",nullable=false) private long sanctionId;
 @Column(name="approver_id",nullable=false) private long approverId;
 @Column(name="note",length=500) private String note;
 @Column(name="approved_at",nullable=false) private Instant approvedAt;
 public static StoreSanctionApproval approve(long sanctionId,long approverId,String note,Instant now){var a=new StoreSanctionApproval();a.sanctionId=sanctionId;a.approverId=approverId;a.note=note;a.approvedAt=now;return a;}
}
