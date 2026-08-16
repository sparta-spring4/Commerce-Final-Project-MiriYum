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
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public StoreSanctionCaseService(StoreSanctionCaseRepository cases, StoreAdministrationService stores,
                                    AdminCaseAssignmentManager assignments,
                                    AdminCaseAssignmentVerifier assignmentVerifier,
                                    OperatorAuthorityReader authorities, IdempotencyExecutor idempotency, Clock clock) {
        this.cases = cases; this.stores = stores; this.assignments = assignments;
        this.assignmentVerifier = assignmentVerifier; this.authorities = authorities;
        this.idempotency=idempotency; this.clock = clock;
    }

    @Transactional
    public IdempotentOutcome create(IdempotencyCommand command,PlatformOperatorPrincipal principal, long storeId, CaseCreate request) {
        return idempotency.execute(command,()->{requirePermission(principal); stores.requireStoreExists(storeId);
            CaseData data=cases.save(StoreSanctionCase.create(storeId, principal.accountId(), request.violationType(),
                    request.evidenceReferences(), request.policyVersion(), clock.instant())).data();
            return success(HttpStatus.CREATED,data.caseId(),data);});
    }

    @Transactional
    public IdempotentOutcome assign(IdempotencyCommand command,PlatformOperatorPrincipal principal, long storeId, String caseId,
                           long expectedCaseVersion) {
        return idempotency.execute(command,()->{requirePermission(principal);
            StoreSanctionCase value = locked(storeId, caseId);
            value.assign(principal.accountId(), expectedCaseVersion);
            assignments.assign(new AdminCaseAssignmentCommand(AdminCaseType.STORE_ENFORCEMENT,
                    caseId, value.getCaseVersion(), principal.accountId(),
                    clock.instant().plus(Duration.ofMinutes(30))));
            CaseData data=value.data();return success(HttpStatus.OK,caseId,data);});
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
    private void requirePermission(PlatformOperatorPrincipal principal) {
        if (!authorities.requireCurrentAuthority(principal.accountId(), principal.authorityVersion())
                .permissions().contains(STORE_SANCTION)) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }
    private static <T> BusinessResult<T> success(HttpStatus status,String id,T data){return new BusinessResult<>(
            status.value(),"SUCCESS","STORE_SANCTION_CASE",id,data);}
}
