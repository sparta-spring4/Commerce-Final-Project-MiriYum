package com.miriyum.domain.platformoperator.adminstore.service;

import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.STORE_SANCTION;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.CaseCreate;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.CaseData;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionCase;
import com.miriyum.domain.platformoperator.adminstore.exception.AdminStoreErrorCode;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionCaseRepository;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.service.*;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

@Service
@ConditionalOnProperty(prefix="miriyum.platform-operator",name="enabled",havingValue="true")
public class StoreSanctionCaseService {
    private final StoreSanctionCaseRepository cases;
    private final StoreAdministrationService stores;
    private final AdminCaseAssignmentManager assignments;
    private final AdminCaseAssignmentVerifier assignmentVerifier;
    private final OperatorAuthorityReader authorities;
    private final PlatformOperatorAuditWriter audit;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public StoreSanctionCaseService(StoreSanctionCaseRepository cases, StoreAdministrationService stores,
                                    AdminCaseAssignmentManager assignments,
                                    AdminCaseAssignmentVerifier assignmentVerifier,
                                    OperatorAuthorityReader authorities, PlatformOperatorAuditWriter audit,
                                    IdempotencyExecutor idempotency, Clock clock) {
        this.cases = cases; this.stores = stores; this.assignments = assignments;
        this.assignmentVerifier = assignmentVerifier; this.authorities = authorities; this.audit=audit;
        this.idempotency=idempotency; this.clock = clock;
    }

    @Transactional
    public IdempotentOutcome create(IdempotencyCommand command,PlatformOperatorPrincipal principal, long storeId, CaseCreate request,
                                    PlatformOperatorAuditReason reason,String correlation) {
        return idempotency.execute(command,()->{var authority=requirePermission(principal); stores.requireStoreExists(storeId);
            var store=stores.inspect(storeId);StoreSanctionCase saved=cases.save(StoreSanctionCase.create(storeId, principal.accountId(), request.violationType(),
                    request.evidenceReferences(), request.policyVersion(), clock.instant()));CaseData data=saved.data();
            audit.appendStore(event(principal,authority,PlatformOperatorAuditAction.STORE_CASE_CREATED,reason,storeId,saved,
                    command.idempotencyKey(),store.enforcementVersion(),Map.of("store",AdminStoreAuditSnapshots.store(store)),
                    Map.of("store",AdminStoreAuditSnapshots.store(store),"case",AdminStoreAuditSnapshots.caseData(data)),correlation));
            return success(HttpStatus.CREATED,data.caseId(),data);});
    }

    @Transactional
    public IdempotentOutcome assign(IdempotencyCommand command,PlatformOperatorPrincipal principal, long storeId, String caseId,
                           long expectedCaseVersion,PlatformOperatorAuditReason reason,String correlation) {
        return idempotency.execute(command,()->{var authority=requirePermission(principal);
            StoreSanctionCase value = locked(storeId, caseId);
            var before=value.data();
            value.assign(principal.accountId(), expectedCaseVersion);
            assignments.assign(new AdminCaseAssignmentCommand(AdminCaseType.STORE_ENFORCEMENT,
                    caseId, value.getCaseVersion(), principal.accountId(),
                    clock.instant().plus(Duration.ofMinutes(30))));
            CaseData data=value.data();audit.appendStore(event(principal,authority,PlatformOperatorAuditAction.STORE_CASE_ASSIGNED,
                    reason,storeId,value,command.idempotencyKey(),null,Map.of("case",AdminStoreAuditSnapshots.caseData(before)),
                    Map.of("case",AdminStoreAuditSnapshots.caseData(data)),correlation));
            return success(HttpStatus.OK,caseId,data);});
    }

    @Transactional
    public StoreSanctionCase requireAssigned(PlatformOperatorPrincipal principal, long storeId,
                                              String caseId, long caseVersion) {
        requirePermission(principal);
        StoreSanctionCase value = locked(storeId, caseId);
        if (value.getCaseVersion() != caseVersion) {
            throw new ServiceException(AdminStoreErrorCode.STORE_CASE_STATE_CONFLICT);
        }
        assignmentVerifier.verify(new AdminCaseAssignmentRequest(AdminCaseType.STORE_ENFORCEMENT,
                caseId, caseVersion, principal.accountId()));
        return value;
    }

    private StoreSanctionCase locked(long storeId, String caseId) {
        return cases.findByPublicIdAndStoreIdForUpdate(caseId, storeId)
                .orElseThrow(() -> new ServiceException(AdminStoreErrorCode.STORE_CASE_NOT_FOUND));
    }
    private com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority requirePermission(PlatformOperatorPrincipal principal) {
        var authority=authorities.requireCurrentAuthority(principal.accountId(), principal.authorityVersion());
        if (!authority.permissions().contains(STORE_SANCTION)) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
        return authority;
    }
    private static PlatformOperatorAuditWriter.StoreEvent event(PlatformOperatorPrincipal principal,
            com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority authority,
            PlatformOperatorAuditAction action,PlatformOperatorAuditReason reason,long storeId,StoreSanctionCase value,
            String key,Long enforcementVersion,Map<String,Object> before,Map<String,Object> after,String correlation){return new PlatformOperatorAuditWriter.StoreEvent(
            principal.accountId(),authority.authorityVersion(),authority.roles(),authority.permissions(),action,
            PlatformOperatorAuditOutcome.SUCCESS,reason,"STORE_SANCTION_CASE",value.getPublicId(),storeId,
            value.getPublicId(),value.getCaseVersion(),null,null,enforcementVersion,key,before,after,correlation);}
    private static <T> BusinessResult<T> success(HttpStatus status,String id,T data){return new BusinessResult<>(
            status.value(),"SUCCESS","STORE_SANCTION_CASE",id,data);}
}
