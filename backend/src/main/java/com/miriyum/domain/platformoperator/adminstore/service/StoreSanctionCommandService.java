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
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.PermanentClosureCause;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.PermanentClosureCommand;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.*;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;

@Service
@ConditionalOnProperty(prefix="miriyum.platform-operator",name="enabled",havingValue="true")
public class StoreSanctionCommandService {
 private final StoreSanctionCaseService cases; private final StoreSanctionRepository sanctions;
 private final StoreSanctionApprovalRepository approvals; private final StoreSanctionImpactService impacts;
 private final StoreSanctionPolicyCatalog policy; private final StoreAdministrationService stores;
 private final HighRiskCommandGuard guard; private final OperatorAuthorityReader authorities;
 private final PlatformOperatorAuditWriter audit; private final Clock clock;
 private final IdempotencyExecutor idempotency;
 public StoreSanctionCommandService(StoreSanctionCaseService cases,StoreSanctionRepository sanctions,
  StoreSanctionApprovalRepository approvals,StoreSanctionImpactService impacts,StoreSanctionPolicyCatalog policy,
  StoreAdministrationService stores,HighRiskCommandGuard guard,OperatorAuthorityReader authorities,
  PlatformOperatorAuditWriter audit,IdempotencyExecutor idempotency,Clock clock){this.cases=cases;this.sanctions=sanctions;this.approvals=approvals;
  this.impacts=impacts;this.policy=policy;this.stores=stores;this.guard=guard;this.authorities=authorities;this.audit=audit;this.idempotency=idempotency;this.clock=clock;}

 @Transactional
 public IdempotentOutcome create(IdempotencyCommand command,PlatformOperatorPrincipal principal,long storeId,String caseId,long caseVersion,
                            SanctionCreate request,PlatformOperatorAuditReason reason,String correlationId){return idempotency.execute(command,()->{
  StoreSanctionCase c=cases.requireAssigned(principal,storeId,caseId,caseVersion);
  SanctionShape shape=new SanctionShape(request.type(),request.restrictedFeatures(),request.startsAt(),request.endsAt());
  policy.validate(shape,clock.instant()); if(policy.requiresImpact(shape)){
   if(request.impactConfirmation()==null)throw new ServiceException(AdminStoreErrorCode.IMPACT_CONFIRMATION_REQUIRED);
   impacts.verify(storeId,caseId,request.impactConfirmation(),shape);
  }
  var current=stores.inspect(storeId); var before=Map.<String,Object>of(
          "case",AdminStoreAuditSnapshots.caseData(c.data()),"store",AdminStoreAuditSnapshots.store(current));
  boolean approval=policy.requiresApproval(shape);
  StoreSanction s=sanctions.saveAndFlush(StoreSanction.create(caseId,storeId,request.type(),
          request.restrictedFeatures(),request.reason(),request.startsAt(),request.endsAt(),principal.accountId(),
          approval,current.enforcementVersion(),clock.instant()));
  boolean applied=false;if(approval)c.pendingApproval(); else { c.activate(); if(request.type()!=StoreSanctionEnums.SanctionType.WARNING){
    s.enforced(stores.apply(policy.command(storeId,current.enforcementVersion(),s.getId(),current,shape)).enforcementVersion());applied=true;} }
  var afterStore=stores.inspect(storeId);append(principal,PlatformOperatorAuditAction.STORE_SANCTION_PROPOSED,reason,
          storeId,c,s,command.idempotencyKey(),before,Map.of("case",AdminStoreAuditSnapshots.caseData(c.data()),
                  "sanction",AdminStoreAuditSnapshots.sanction(s.data()),"store",AdminStoreAuditSnapshots.store(afterStore)),correlationId);
  if(applied)append(principal,PlatformOperatorAuditAction.STORE_SANCTION_APPLIED,reason,storeId,c,s,command.idempotencyKey(),
          Map.of("case",AdminStoreAuditSnapshots.caseData(c.data()),"store",AdminStoreAuditSnapshots.store(current)),
          Map.of("case",AdminStoreAuditSnapshots.caseData(c.data()),"sanction",AdminStoreAuditSnapshots.sanction(s.data()),
                  "store",AdminStoreAuditSnapshots.store(afterStore)),correlationId);
  SanctionData data=s.data();return success(HttpStatus.CREATED,s.getId(),data);
  });
 }

 @Transactional
 public IdempotentOutcome approve(IdempotencyCommand command,PlatformOperatorPrincipal principal,long storeId,String caseId,long caseVersion,long sanctionId,
                             SanctionApproval request,String approvalToken,PlatformOperatorAuditReason reason,String correlationId){return idempotency.execute(command,()->{
  StoreSanctionCase c=cases.requireAssigned(principal,storeId,caseId,caseVersion);
  StoreSanction s=locked(storeId,caseId,sanctionId); if(s.getCreatedBy()==principal.accountId())
    throw new ServiceException(com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
  SanctionShape shape=new SanctionShape(s.getType(),s.getRestrictedFeatures(),s.getStartsAt(),s.getEndsAt());
  impacts.verify(storeId,caseId,request.impactConfirmation(),shape);
  AdminAuditContext context=guard.authorize(highRisk(principal,storeId,caseId,caseVersion,approvalToken,correlationId));
  var current=stores.inspect(storeId);var before=Map.<String,Object>of(
          "case",AdminStoreAuditSnapshots.caseData(c.data()),"sanction",AdminStoreAuditSnapshots.sanction(s.data()),
          "store",AdminStoreAuditSnapshots.store(current));
  StoreSanctionApproval approval=approvals.saveAndFlush(StoreSanctionApproval.approve(
          sanctionId,principal.accountId(),request.note(),clock.instant()));
  var applied=s.getType()==StoreSanctionEnums.SanctionType.PERMANENT_EXIT
          ? stores.closePermanently(new PermanentClosureCommand(
                  storeId,current.enforcementVersion(),s.getId(),approval.getId(),
                  PermanentClosureCause.PLATFORM_SANCTION,c.getPolicyVersion()))
          : stores.apply(policy.command(storeId,current.enforcementVersion(),s.getId(),current,shape));
  s.approve(request.expectedSanctionVersion(),applied.enforcementVersion());c.activate();
  var after=Map.<String,Object>of("case",AdminStoreAuditSnapshots.caseData(c.data()),
          "sanction",AdminStoreAuditSnapshots.sanction(s.data()),"store",AdminStoreAuditSnapshots.store(applied));
  audit.appendStore(event(context,PlatformOperatorAuditAction.STORE_SANCTION_APPROVED,reason,storeId,c,s,command.idempotencyKey(),before,after,correlationId));
  audit.appendStore(event(context,PlatformOperatorAuditAction.STORE_SANCTION_APPLIED,reason,storeId,c,s,command.idempotencyKey(),before,after,correlationId));
  return success(HttpStatus.OK,s.getId(),s.data());});
 }

 @Transactional
 public IdempotentOutcome release(IdempotencyCommand command,PlatformOperatorPrincipal principal,long storeId,String caseId,long caseVersion,long sanctionId,
                             SanctionRelease request,String approvalToken,PlatformOperatorAuditReason reason,String correlationId){return idempotency.execute(command,()->{
  StoreSanctionCase c=cases.requireAssigned(principal,storeId,caseId,caseVersion); StoreSanction s=locked(storeId,caseId,sanctionId);
  policy.validateRelease(s.getType());
  AdminAuditContext context=guard.authorize(highRisk(principal,storeId,caseId,caseVersion,approvalToken,correlationId));
  var before=Map.<String,Object>of("case",AdminStoreAuditSnapshots.caseData(c.data()),
          "sanction",AdminStoreAuditSnapshots.sanction(s.data()),"store",AdminStoreAuditSnapshots.store(stores.inspect(storeId)));
  var result=stores.release(new ReleaseCommand(storeId,sanctionId));
  s.release(request.expectedSanctionVersion(),result.enforcementVersion(),clock.instant());c.resolve();
  audit.appendStore(event(context,PlatformOperatorAuditAction.STORE_SANCTION_RELEASED,reason,storeId,c,s,command.idempotencyKey(),before,
          Map.of("case",AdminStoreAuditSnapshots.caseData(c.data()),"sanction",AdminStoreAuditSnapshots.sanction(s.data()),
                  "store",AdminStoreAuditSnapshots.store(result)),correlationId));return success(HttpStatus.OK,s.getId(),s.data());});
 }
 private StoreSanction locked(long storeId,String caseId,long id){return sanctions.findScopedForUpdate(id,caseId,storeId).orElseThrow(()->new ServiceException(AdminStoreErrorCode.SANCTION_NOT_FOUND));}
 private HighRiskCommandRequest highRisk(PlatformOperatorPrincipal p,long storeId,String caseId,long version,String token,String correlation){return new HighRiskCommandRequest(p,STORE_SANCTION,AdminCaseType.STORE_ENFORCEMENT,caseId,version,AdminCommandPurpose.STORE_SANCTION,AdminTargetType.STORE,String.valueOf(storeId),token,correlation);}
 private void append(PlatformOperatorPrincipal p,PlatformOperatorAuditAction action,PlatformOperatorAuditReason reason,long storeId,
                     StoreSanctionCase c,StoreSanction s,String key,Map<String,Object> before,Map<String,Object> after,String correlation){
  var a=authorities.requireCurrentAuthority(p.accountId(),p.authorityVersion());audit.appendStore(new PlatformOperatorAuditWriter.StoreEvent(
          p.accountId(),a.authorityVersion(),a.roles(),a.permissions(),action,PlatformOperatorAuditOutcome.SUCCESS,reason,
          "STORE_SANCTION",String.valueOf(s.getId()),storeId,c.getPublicId(),c.getCaseVersion(),s.getId(),
          s.getSanctionVersion(),s.getStoreEnforcementVersion(),key,before,after,correlation==null?UUID.randomUUID().toString():correlation));}
 private static PlatformOperatorAuditWriter.StoreEvent event(AdminAuditContext context,PlatformOperatorAuditAction action,
          PlatformOperatorAuditReason reason,long storeId,StoreSanctionCase c,StoreSanction s,String key,
          Map<String,Object> before,Map<String,Object> after,String correlation){return new PlatformOperatorAuditWriter.StoreEvent(
          context.operatorId(),context.authorityVersion(),context.roles(),context.permissions(),action,PlatformOperatorAuditOutcome.SUCCESS,
          reason,"STORE_SANCTION",String.valueOf(s.getId()),storeId,c.getPublicId(),c.getCaseVersion(),s.getId(),
          s.getSanctionVersion(),s.getStoreEnforcementVersion(),key,before,after,correlation);}
 private static <T> BusinessResult<T> success(HttpStatus status,Long id,T data){return new BusinessResult<>(status.value(),"SUCCESS","STORE_SANCTION",String.valueOf(id),data);}
}
