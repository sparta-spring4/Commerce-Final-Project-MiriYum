package com.miriyum.domain.platformoperator.adminstore.service;

import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.STORE_SANCTION;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.*;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.SanctionData;
import com.miriyum.domain.platformoperator.adminstore.entity.*;
import com.miriyum.domain.platformoperator.adminstore.exception.AdminStoreErrorCode;
import com.miriyum.domain.platformoperator.adminstore.model.StoreSanctionPolicyCatalog;
import com.miriyum.domain.platformoperator.adminstore.repository.*;
import com.miriyum.domain.platformoperator.dto.authorization.*;
import com.miriyum.domain.platformoperator.enums.*;
import com.miriyum.domain.platformoperator.service.*;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.ReleaseCommand;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreSanctionCommandService {
 private final StoreSanctionCaseService cases; private final StoreSanctionRepository sanctions;
 private final StoreSanctionApprovalRepository approvals; private final StoreSanctionImpactService impacts;
 private final StoreSanctionPolicyCatalog policy; private final StoreAdministrationService stores;
 private final HighRiskCommandGuard guard; private final OperatorAuthorityReader authorities;
 private final PlatformOperatorAuditWriter audit; private final Clock clock;
 public StoreSanctionCommandService(StoreSanctionCaseService cases,StoreSanctionRepository sanctions,
  StoreSanctionApprovalRepository approvals,StoreSanctionImpactService impacts,StoreSanctionPolicyCatalog policy,
  StoreAdministrationService stores,HighRiskCommandGuard guard,OperatorAuthorityReader authorities,
  PlatformOperatorAuditWriter audit,Clock clock){this.cases=cases;this.sanctions=sanctions;this.approvals=approvals;
  this.impacts=impacts;this.policy=policy;this.stores=stores;this.guard=guard;this.authorities=authorities;this.audit=audit;this.clock=clock;}

 @Transactional
 public SanctionData create(PlatformOperatorPrincipal principal,long storeId,String caseId,long caseVersion,
                            SanctionCreate request,String idempotencyKey,String correlationId){
  StoreSanctionCase c=cases.requireAssigned(principal,storeId,caseId,caseVersion);
  SanctionShape shape=new SanctionShape(request.type(),request.restrictedFeatures(),request.startsAt(),request.endsAt());
  policy.validate(shape,clock.instant()); if(policy.requiresImpact(shape)){
   if(request.impactConfirmation()==null)throw new ServiceException(AdminStoreErrorCode.IMPACT_CONFIRMATION_REQUIRED);
   impacts.verify(storeId,caseId,request.impactConfirmation(),shape);
  }
  var current=stores.inspect(storeId); boolean approval=policy.requiresApproval(shape);
  StoreSanction s=sanctions.saveAndFlush(StoreSanction.create(caseId,storeId,request.type(),
          request.restrictedFeatures(),request.reason(),request.startsAt(),request.endsAt(),principal.accountId(),
          approval,current.enforcementVersion(),clock.instant()));
  if(approval)c.pendingApproval(); else { c.activate(); if(request.type()!=StoreSanctionEnums.SanctionType.WARNING)
    s.enforced(stores.apply(policy.command(storeId,current.enforcementVersion(),s.getId(),current,shape)).enforcementVersion()); }
  append(principal,PlatformOperatorAuditAction.STORE_SANCTION_CREATED,storeId,caseId,caseVersion,idempotencyKey,correlationId);
  return s.data();
 }

 @Transactional
 public SanctionData approve(PlatformOperatorPrincipal principal,long storeId,String caseId,long caseVersion,long sanctionId,
                             SanctionApproval request,String approvalToken,String idempotencyKey,String correlationId){
  StoreSanctionCase c=cases.requireAssigned(principal,storeId,caseId,caseVersion);
  StoreSanction s=locked(storeId,caseId,sanctionId); if(s.getCreatedBy()==principal.accountId())
    throw new ServiceException(com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
  SanctionShape shape=new SanctionShape(s.getType(),s.getRestrictedFeatures(),s.getStartsAt(),s.getEndsAt());
  impacts.verify(storeId,caseId,request.impactConfirmation(),shape);
  AdminAuditContext context=guard.authorize(highRisk(principal,storeId,caseId,caseVersion,approvalToken,correlationId));
  var current=stores.inspect(storeId); var applied=stores.apply(policy.command(storeId,current.enforcementVersion(),s.getId(),current,shape));
  s.approve(request.expectedSanctionVersion(),applied.enforcementVersion()); approvals.save(StoreSanctionApproval.approve(sanctionId,principal.accountId(),request.note(),clock.instant()));c.activate();
  audit.appendStore(new PlatformOperatorAuditWriter.StoreEvent(context.operatorId(),context.authorityVersion(),context.roles(),context.permissions(),PlatformOperatorAuditAction.STORE_SANCTION_APPROVED,String.valueOf(storeId),caseId,caseVersion,idempotencyKey,correlationId));
  return s.data();
 }

 @Transactional
 public SanctionData release(PlatformOperatorPrincipal principal,long storeId,String caseId,long caseVersion,long sanctionId,
                             SanctionRelease request,String approvalToken,String idempotencyKey,String correlationId){
  StoreSanctionCase c=cases.requireAssigned(principal,storeId,caseId,caseVersion); StoreSanction s=locked(storeId,caseId,sanctionId);
  AdminAuditContext context=guard.authorize(highRisk(principal,storeId,caseId,caseVersion,approvalToken,correlationId));
  var result=stores.release(new ReleaseCommand(storeId,s.getStoreEnforcementVersion(),sanctionId));
  s.release(request.expectedSanctionVersion(),result.enforcementVersion(),clock.instant());c.resolve();
  audit.appendStore(new PlatformOperatorAuditWriter.StoreEvent(context.operatorId(),context.authorityVersion(),context.roles(),context.permissions(),PlatformOperatorAuditAction.STORE_SANCTION_RELEASED,String.valueOf(storeId),caseId,caseVersion,idempotencyKey,correlationId));return s.data();
 }
 private StoreSanction locked(long storeId,String caseId,long id){return sanctions.findScopedForUpdate(id,caseId,storeId).orElseThrow(()->new ServiceException(AdminStoreErrorCode.SANCTION_NOT_FOUND));}
 private HighRiskCommandRequest highRisk(PlatformOperatorPrincipal p,long storeId,String caseId,long version,String token,String correlation){return new HighRiskCommandRequest(p,STORE_SANCTION,AdminCaseType.STORE_ENFORCEMENT,caseId,version,AdminCommandPurpose.STORE_SANCTION,AdminTargetType.STORE,String.valueOf(storeId),token,correlation);}
 private void append(PlatformOperatorPrincipal p,PlatformOperatorAuditAction action,long storeId,String caseId,long version,String key,String correlation){var a=authorities.requireCurrentAuthority(p.accountId(),p.authorityVersion());audit.appendStore(new PlatformOperatorAuditWriter.StoreEvent(p.accountId(),a.authorityVersion(),a.roles(),a.permissions(),action,String.valueOf(storeId),caseId,version,key,correlation==null?UUID.randomUUID().toString():correlation));}
}
