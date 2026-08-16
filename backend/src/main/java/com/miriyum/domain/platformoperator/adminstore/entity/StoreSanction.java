package com.miriyum.domain.platformoperator.adminstore.entity;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.SanctionData;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.*;
import com.miriyum.domain.platformoperator.adminstore.exception.AdminStoreErrorCode;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="store_sanctions") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class StoreSanction {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="store_sanction_id") private Long id;
    @Column(name="case_public_id", nullable=false, length=36) private String caseId;
    @Column(name="store_id", nullable=false) private long storeId;
    @Enumerated(EnumType.STRING) @Column(name="sanction_type", nullable=false, length=30) private SanctionType type;
    @Enumerated(EnumType.STRING) @Column(name="status", nullable=false, length=30) private SanctionStatus status;
    @Column(name="sanction_version", nullable=false) private long sanctionVersion;
    @Column(name="store_enforcement_version", nullable=false) private long storeEnforcementVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="restricted_features", nullable=false, columnDefinition="json")
    private Set<RestrictedFeature> restrictedFeatures;
    @Column(name="reason", nullable=false, length=1000) private String reason;
    @Column(name="starts_at") private Instant startsAt;
    @Column(name="ends_at") private Instant endsAt;
    @Column(name="created_by", nullable=false) private long createdBy;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="released_at") private Instant releasedAt;

    public static StoreSanction create(String caseId,long storeId,SanctionType type,Set<RestrictedFeature> features,
            String reason,Instant startsAt,Instant endsAt,long creator,boolean approvalRequired,long enforcementVersion,Instant now) {
        StoreSanction s=new StoreSanction(); s.caseId=caseId;s.storeId=storeId;s.type=type;
        s.restrictedFeatures=Set.copyOf(features);s.reason=reason;s.startsAt=startsAt;s.endsAt=endsAt;
        s.createdBy=creator;s.createdAt=now;s.sanctionVersion=1;s.storeEnforcementVersion=enforcementVersion;
        s.status=approvalRequired?SanctionStatus.PENDING_APPROVAL:SanctionStatus.ACTIVE;return s;
    }
    public void approve(long expectedVersion,long enforcementVersion) {
        if(status!=SanctionStatus.PENDING_APPROVAL||sanctionVersion!=expectedVersion) conflict();
        status=SanctionStatus.ACTIVE;sanctionVersion++;storeEnforcementVersion=enforcementVersion;
    }
    public void release(long expectedVersion,long enforcementVersion,Instant now) {
        if(status!=SanctionStatus.ACTIVE||sanctionVersion!=expectedVersion) conflict();
        status=SanctionStatus.RELEASED;sanctionVersion++;storeEnforcementVersion=enforcementVersion;releasedAt=now;
    }
    public void expire(long expectedVersion,long enforcementVersion,Instant now) {
        if(type!=SanctionType.TEMPORARY_SUSPENSION||status!=SanctionStatus.ACTIVE
                || sanctionVersion!=expectedVersion||endsAt==null||endsAt.isAfter(now)) conflict();
        status=SanctionStatus.EXPIRED;sanctionVersion++;storeEnforcementVersion=enforcementVersion;releasedAt=now;
    }
    public void enforced(long version){storeEnforcementVersion=version;}
    public SanctionData data(){return new SanctionData(id,caseId,storeId,type,status,sanctionVersion,
            storeEnforcementVersion,restrictedFeatures,startsAt,endsAt,createdAt);}
    private static void conflict(){throw new ServiceException(AdminStoreErrorCode.SANCTION_STATE_CONFLICT);}
}
