package com.miriyum.domain.platformoperator.adminstore.service;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.*;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.*;
import com.miriyum.domain.platformoperator.adminstore.repository.*;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.service.*;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.exception.ServiceException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service @ConditionalOnProperty(prefix="miriyum.platform-operator",name="enabled",havingValue="true") public class AdminStoreQueryService {
 private final StoreAdministrationService stores; private final StoreSanctionRepository sanctions;
 private final StoreSanctionCaseRepository cases; private final StoreSanctionCaseService caseAccess;
 private final OperatorAuthorityReader authorities;
 private final PlatformOperatorAuditWriter audit;
 public AdminStoreQueryService(StoreAdministrationService stores,StoreSanctionRepository sanctions,
  StoreSanctionCaseRepository cases,StoreSanctionCaseService caseAccess,OperatorAuthorityReader authorities,
  PlatformOperatorAuditWriter audit){this.stores=stores;this.sanctions=sanctions;this.cases=cases;this.caseAccess=caseAccess;this.authorities=authorities;this.audit=audit;}
 @Transactional public StorePage search(PlatformOperatorPrincipal p,String keyword,OperationStatus status,int page,int size,
  PlatformOperatorAuditReason reason,String correlation){var authority=read(p);var result=stores.search(keyword,status,page,size);var ids=result.content().stream().map(s->s.storeId()).toList();var active=active(ids);var data=new StorePage(result.content().stream().map(s->new StoreSummary(s.storeId(),s.name(),s.storeOperatorAccountId(),s.operationStatus(),s.reservationEnabled(),s.menuHoldEnabled(),s.pickupEnabled(),s.enforcementVersion(),active.getOrDefault(s.storeId(),Set.of()))).toList(),page,size,result.totalElements(),result.totalPages());
  audit.appendStore(event(p,authority,PlatformOperatorAuditAction.STORE_SEARCHED,reason,"STORE_COLLECTION","ALL",null,
          "store-search-"+p.accountId(),1L,null,null,null,Map.of(),Map.of("resultCount",data.content().size()),correlation));return data;}
 @Transactional public StoreDetail get(PlatformOperatorPrincipal p,long storeId,PlatformOperatorAuditReason reason,String correlation){var authority=read(p);var s=stores.get(storeId);var active=active(List.of(storeId));var data=new StoreDetail(s.storeId(),s.name(),s.storeOperatorAccountId(),s.operationStatus(),s.reservationEnabled(),s.menuHoldEnabled(),s.pickupEnabled(),s.enforcementVersion(),active.getOrDefault(storeId,Set.of()),s.createdAt(),cases.findOpenPublicIdsByStoreId(storeId));
  audit.appendStore(event(p,authority,PlatformOperatorAuditAction.STORE_DETAIL_READ,reason,"STORE",String.valueOf(storeId),storeId,
          "store-read-"+storeId,1L,null,null,s.enforcementVersion(),Map.of(),Map.of("store",AdminStoreAuditSnapshots.store(s)),correlation));return data;}
 @Transactional public CaseDetail caseDetail(PlatformOperatorPrincipal p,long storeId,String caseId,long version,
  PlatformOperatorAuditReason reason,String correlation){var c=caseAccess.requireAssigned(p,storeId,caseId,version);var authority=authorities.requireCurrentAuthority(p.accountId(),p.authorityVersion());var data=new CaseDetail(c.getPublicId(),c.getStoreId(),c.getViolationType(),c.getEvidenceReferences(),c.getPolicyVersion(),c.getStatus(),c.getCaseVersion(),c.getCreatedBy(),c.getAssignedOperatorId(),c.getSubmittedAt(),sanctions.findByCaseIdOrderByIdAsc(caseId).stream().map(s->s.data()).toList());var store=stores.inspect(storeId);
  audit.appendStore(event(p,authority,PlatformOperatorAuditAction.STORE_CASE_DETAIL_READ,reason,"STORE_SANCTION_CASE",caseId,storeId,caseId,c.getCaseVersion(),null,null,store.enforcementVersion(),Map.of(),Map.of("case",AdminStoreAuditSnapshots.caseDetail(data),"store",AdminStoreAuditSnapshots.store(store)),correlation));return data;}
 private Map<Long,Set<SanctionType>> active(List<Long> ids){if(ids.isEmpty())return Map.of();return sanctions.findByStoreIdInAndStatus(ids,SanctionStatus.ACTIVE).stream().collect(Collectors.groupingBy(s->s.getStoreId(),Collectors.mapping(s->s.getType(),Collectors.toUnmodifiableSet())));}
 private com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority read(PlatformOperatorPrincipal p){var authority=authorities.requireCurrentAuthority(p.accountId(),p.authorityVersion());if(!authority.permissions().contains(PlatformOperatorPermission.STORE_READ_MINIMAL))throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);return authority;}
 private static PlatformOperatorAuditWriter.StoreEvent event(PlatformOperatorPrincipal p,
  com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority authority,PlatformOperatorAuditAction action,
  PlatformOperatorAuditReason reason,String targetType,String targetId,Long storeId,String caseId,long caseVersion,
  Long sanctionId,Long sanctionVersion,Long enforcementVersion,Map<String,Object> before,Map<String,Object> after,String correlation){return new PlatformOperatorAuditWriter.StoreEvent(
  p.accountId(),authority.authorityVersion(),authority.roles(),authority.permissions(),action,PlatformOperatorAuditOutcome.SUCCESS,
  reason,targetType,targetId,storeId,caseId,caseVersion,sanctionId,sanctionVersion,enforcementVersion,null,before,after,correlation);}
}
