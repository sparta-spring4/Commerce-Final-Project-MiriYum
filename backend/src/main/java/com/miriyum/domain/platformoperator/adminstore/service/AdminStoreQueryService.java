package com.miriyum.domain.platformoperator.adminstore.service;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.*;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.*;
import com.miriyum.domain.platformoperator.adminstore.repository.*;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
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
 public AdminStoreQueryService(StoreAdministrationService stores,StoreSanctionRepository sanctions,
  StoreSanctionCaseRepository cases,StoreSanctionCaseService caseAccess,OperatorAuthorityReader authorities){this.stores=stores;this.sanctions=sanctions;this.cases=cases;this.caseAccess=caseAccess;this.authorities=authorities;}
 @Transactional(readOnly=true) public StorePage search(PlatformOperatorPrincipal p,String keyword,OperationStatus status,int page,int size){read(p);var result=stores.search(keyword,status,page,size);var ids=result.content().stream().map(s->s.storeId()).toList();var active=active(ids);return new StorePage(result.content().stream().map(s->new StoreSummary(s.storeId(),s.name(),s.storeOperatorAccountId(),s.operationStatus(),s.reservationEnabled(),s.menuHoldEnabled(),s.pickupEnabled(),s.enforcementVersion(),active.getOrDefault(s.storeId(),Set.of()))).toList(),page,size,result.totalElements(),result.totalPages());}
 @Transactional(readOnly=true) public StoreDetail get(PlatformOperatorPrincipal p,long storeId){read(p);var s=stores.get(storeId);var active=active(List.of(storeId));return new StoreDetail(s.storeId(),s.name(),s.storeOperatorAccountId(),s.operationStatus(),s.reservationEnabled(),s.menuHoldEnabled(),s.pickupEnabled(),s.enforcementVersion(),active.getOrDefault(storeId,Set.of()),s.createdAt(),cases.findOpenPublicIdsByStoreId(storeId));}
 @Transactional public CaseDetail caseDetail(PlatformOperatorPrincipal p,long storeId,String caseId,long version){var c=caseAccess.requireAssigned(p,storeId,caseId,version);return new CaseDetail(c.getPublicId(),c.getStoreId(),c.getViolationType(),c.getEvidenceReferences(),c.getPolicyVersion(),c.getStatus(),c.getCaseVersion(),c.getCreatedBy(),c.getAssignedOperatorId(),c.getSubmittedAt(),sanctions.findByCaseIdOrderByIdAsc(caseId).stream().map(s->s.data()).toList());}
 private Map<Long,Set<SanctionType>> active(List<Long> ids){if(ids.isEmpty())return Map.of();return sanctions.findByStoreIdInAndStatus(ids,SanctionStatus.ACTIVE).stream().collect(Collectors.groupingBy(s->s.getStoreId(),Collectors.mapping(s->s.getType(),Collectors.toUnmodifiableSet())));}
 private void read(PlatformOperatorPrincipal p){if(!authorities.requireCurrentAuthority(p.accountId(),p.authorityVersion()).permissions().contains(PlatformOperatorPermission.STORE_READ_MINIMAL))throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);}
}
