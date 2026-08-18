package com.miriyum.domain.platformoperator.adminstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.CaseCreate;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionCase;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionCaseRepository;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementResult;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class StoreSanctionCaseServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
    private static final PlatformOperatorPrincipal PRINCIPAL =
            new PlatformOperatorPrincipal(17L, "admin@example.com", "sid", 3, 4, false);

    @Test
    void createsStoreScopedCaseAndAssignsItsNewVersionOnly() {
        StoreSanctionCaseRepository cases = mock(StoreSanctionCaseRepository.class);
        StoreAdministrationService stores = mock(StoreAdministrationService.class);
        AdminCaseAssignmentManager assignments = mock(AdminCaseAssignmentManager.class);
        OperatorAuthorityReader authorities = mock(OperatorAuthorityReader.class);
        IdempotencyExecutor idempotency = executingIdempotency();
        given(authorities.requireCurrentAuthority(17L, 3L)).willReturn(new OperatorAuthority(
                17L, 3L, Set.of(), Set.of(PlatformOperatorPermission.STORE_SANCTION)));
        given(cases.save(org.mockito.ArgumentMatchers.any())).willAnswer(invocation -> invocation.getArgument(0));
        given(stores.inspect(101L)).willReturn(new EnforcementResult(101L,0L,OperationStatus.OPEN,
                true,true,true,true,true,Set.of()));
        StoreSanctionCaseService service = new StoreSanctionCaseService(
                cases, stores, assignments, mock(com.miriyum.domain.platformoperator.service.AdminCaseAssignmentVerifier.class),
                authorities, mock(PlatformOperatorAuditWriter.class), idempotency, CLOCK);

        var created = service.create(command("STORE_CASE_CREATE"), PRINCIPAL, 101L,
                new CaseCreate("FRAUD", Set.of("evidence://101"), "ADMIN-007-v1"),
                PlatformOperatorAuditReason.STORE_ENFORCEMENT,"correlation-case-create");
        ArgumentCaptor<StoreSanctionCase> savedCase = ArgumentCaptor.forClass(StoreSanctionCase.class);
        then(cases).should().save(savedCase.capture());
        String caseId=created.data().get("caseId").asText();
        given(cases.findByPublicIdAndStoreIdForUpdate(caseId, 101L))
                .willReturn(Optional.of(savedCase.getValue()));

        var assigned = service.assign(command("STORE_CASE_ASSIGN"),PRINCIPAL, 101L, caseId, 1L,
                PlatformOperatorAuditReason.STORE_ENFORCEMENT,"correlation-case-assign");

        assertThat(assigned.data().get("storeId").asLong()).isEqualTo(101L);
        assertThat(assigned.data().get("caseVersion").asLong()).isEqualTo(2L);
        ArgumentCaptor<AdminCaseAssignmentCommand> command =
                ArgumentCaptor.forClass(AdminCaseAssignmentCommand.class);
        then(assignments).should().assign(command.capture());
        assertThat(command.getValue().caseType()).isEqualTo(AdminCaseType.STORE_ENFORCEMENT);
        assertThat(command.getValue().caseId()).isEqualTo(caseId);
        assertThat(command.getValue().caseVersion()).isEqualTo(2L);
        then(stores).should().requireStoreExists(101L);
    }

    @Test
    void rejectsCaseCreationWithoutStoreSanctionPermission() {
        OperatorAuthorityReader authorities=mock(OperatorAuthorityReader.class);
        given(authorities.requireCurrentAuthority(17L,3L)).willReturn(
                new OperatorAuthority(17L,3L,Set.of(),Set.of(PlatformOperatorPermission.STORE_READ_MINIMAL)));
        StoreSanctionCaseService service=new StoreSanctionCaseService(mock(StoreSanctionCaseRepository.class),
                mock(StoreAdministrationService.class),mock(AdminCaseAssignmentManager.class),
                mock(com.miriyum.domain.platformoperator.service.AdminCaseAssignmentVerifier.class),authorities,
                mock(PlatformOperatorAuditWriter.class),executingIdempotency(),CLOCK);
        assertThatThrownBy(()->service.create(command("STORE_CASE_CREATE"),PRINCIPAL,101L,new CaseCreate("FRAUD",Set.of("evidence://101"),"ADMIN-007-v1"),
                PlatformOperatorAuditReason.STORE_ENFORCEMENT,"correlation-denied"))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class)
                .extracting(e->((com.miriyum.global.exception.ServiceException)e).getErrorCode())
                .isEqualTo(com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
    }
    private static IdempotencyCommand command(String type){return new IdempotencyCommand("platform-operator",17L,type,
            "123e4567-e89b-12d3-a456-426614174000","a".repeat(64));}
    @SuppressWarnings("unchecked") private static IdempotencyExecutor executingIdempotency(){IdempotencyExecutor value=mock(IdempotencyExecutor.class);
        given(value.execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).willAnswer(invocation->{
            BusinessResult<Object> result=((java.util.function.Supplier<BusinessResult<Object>>)invocation.getArgument(1)).get();
            return new IdempotentOutcome(false,result.httpStatus(),result.responseCode(),result.resourceType(),result.resourceId(),
                    new ObjectMapper().valueToTree(result.data()));});return value;}
}
