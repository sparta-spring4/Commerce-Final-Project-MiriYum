package com.miriyum.domain.platformoperator.adminstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.SanctionCreate;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.ImpactConfirmation;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.SanctionApproval;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionCase;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionApproval;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionStatus;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanction;
import com.miriyum.domain.platformoperator.adminstore.model.StoreSanctionPolicyCatalog;
import com.miriyum.domain.platformoperator.adminstore.repository.*;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.*;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.service.*;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementResult;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.PermanentClosureCommand;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.idempotency.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class StoreSanctionCommandServiceTest {
    @Test
    void createRunsInsideCommonIdempotencyBoundary() {
        IdempotencyExecutor idempotency=mock(IdempotencyExecutor.class);
        IdempotentOutcome stored=new IdempotentOutcome(true,201,"SUCCESS","STORE_SANCTION","9",
                new ObjectMapper().readTree("{\"sanctionId\":9}"));
        given(idempotency.execute(any(),any())).willReturn(stored);
        StoreSanctionCommandService service=new StoreSanctionCommandService(mock(StoreSanctionCaseService.class),
                mock(StoreSanctionRepository.class),mock(StoreSanctionApprovalRepository.class),
                mock(StoreSanctionImpactService.class),new StoreSanctionPolicyCatalog(),mock(StoreAdministrationService.class),
                mock(HighRiskCommandGuard.class),mock(OperatorAuthorityReader.class),mock(PlatformOperatorAuditWriter.class),
                idempotency,Clock.systemUTC());
        IdempotencyCommand command=new IdempotencyCommand("platform-operator",17L,"STORE_SANCTION_CREATE",
                "123e4567-e89b-12d3-a456-426614174000","a".repeat(64));

        IdempotentOutcome result=service.create(command,new PlatformOperatorPrincipal(17L,"a@b.com","sid",1,1,false),
                10L,"case-1",3L,new SanctionCreate(SanctionType.FEATURE_RESTRICTION,
                        Set.of(RestrictedFeature.RESERVATION),null,null,"reason",null),
                PlatformOperatorAuditReason.STORE_ENFORCEMENT,null);

        assertThat(result).isSameAs(stored);
    }

    @Test
    void dueTemporarySanctionTransitionsToExpired() {
        Instant now=Instant.parse("2026-08-16T00:00:00Z");
        StoreSanction sanction=StoreSanction.create("case-1",10L,SanctionType.TEMPORARY_SUSPENSION,
                Set.of(),"reason",now.minusSeconds(60),now.minusSeconds(1),17L,false,4L,now.minusSeconds(60));

        sanction.expire(1L,5L,now);

        assertThat(sanction.getStatus()).isEqualTo(SanctionStatus.EXPIRED);
        assertThat(sanction.getSanctionVersion()).isEqualTo(2L);
    }

    @Test
    void permanentExitApprovalUsesPersistedApprovalAndCasePolicyForStoreClosure() {
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        StoreSanctionCaseService caseService = mock(StoreSanctionCaseService.class);
        StoreSanctionRepository sanctionRepository = mock(StoreSanctionRepository.class);
        StoreSanctionApprovalRepository approvalRepository = mock(StoreSanctionApprovalRepository.class);
        StoreSanctionImpactService impacts = mock(StoreSanctionImpactService.class);
        StoreAdministrationService stores = mock(StoreAdministrationService.class);
        HighRiskCommandGuard guard = mock(HighRiskCommandGuard.class);
        PlatformOperatorAuditWriter audit = mock(PlatformOperatorAuditWriter.class);
        IdempotencyExecutor idempotency = mock(IdempotencyExecutor.class);
        StoreSanctionCase sanctionCase = StoreSanctionCase.create(
                10L, 17L, "FRAUD", Set.of("evidence://1"), "ADMIN-007-v7", now);
        ReflectionTestUtils.setField(sanctionCase, "publicId", "case-1");
        StoreSanction sanction = StoreSanction.create(
                "case-1", 10L, SanctionType.PERMANENT_EXIT, Set.of(), "reason",
                now, null, 17L, true, 4L, now);
        ReflectionTestUtils.setField(sanction, "id", 9L);
        PlatformOperatorPrincipal principal =
                new PlatformOperatorPrincipal(18L, "approver@example.com", "sid", 2L, 1L, false);
        given(caseService.requireAssigned(principal, 10L, "case-1", 1L))
                .willReturn(sanctionCase);
        given(sanctionRepository.findScopedForUpdate(9L, "case-1", 10L))
                .willReturn(java.util.Optional.of(sanction));
        given(stores.inspect(10L)).willReturn(new EnforcementResult(
                10L, 4L, OperationStatus.OPEN, true, true, true, true, true, Set.of()));
        given(stores.closePermanently(any())).willReturn(new EnforcementResult(
                10L, 5L, OperationStatus.CLOSED, false, false, false, false, false,
                Set.of(RestrictedFeature.values())));
        given(approvalRepository.saveAndFlush(any())).willAnswer(invocation -> {
            StoreSanctionApproval approval = invocation.getArgument(0);
            ReflectionTestUtils.setField(approval, "id", 301L);
            return approval;
        });
        given(guard.authorize(any())).willReturn(new AdminAuditContext(
                18L, Set.of(PlatformOperatorRole.SUPER_ADMIN),
                Set.of(PlatformOperatorPermission.STORE_SANCTION), 2L,
                AdminCaseType.STORE_ENFORCEMENT, "case-1", 1L,
                AdminCommandPurpose.STORE_SANCTION, AdminTargetType.STORE, "10",
                "fingerprint", "correlation"));
        given(idempotency.execute(any(), any())).willAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(1);
            BusinessResult<?> result = (BusinessResult<?>) work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(),
                    new ObjectMapper().valueToTree(result.data()));
        });
        StoreSanctionCommandService service = new StoreSanctionCommandService(
                caseService, sanctionRepository, approvalRepository, impacts,
                new StoreSanctionPolicyCatalog(), stores, guard,
                mock(OperatorAuthorityReader.class), audit, idempotency,
                Clock.fixed(now, java.time.ZoneOffset.UTC));

        service.approve(
                new IdempotencyCommand("platform-operator", 18L, "STORE_SANCTION_APPROVE",
                        "123e4567-e89b-12d3-a456-426614174000", "b".repeat(64)),
                principal, 10L, "case-1", 1L, 9L,
                new SanctionApproval(1L,
                        new ImpactConfirmation(1L, "c".repeat(64), 1L, 4L), "approved"),
                "approval-token", PlatformOperatorAuditReason.STORE_ENFORCEMENT,
                "correlation");

        ArgumentCaptor<PermanentClosureCommand> command =
                ArgumentCaptor.forClass(PermanentClosureCommand.class);
        then(stores).should().closePermanently(command.capture());
        assertThat(command.getValue().sanctionId()).isEqualTo(9L);
        assertThat(command.getValue().approvalId()).isEqualTo(301L);
        assertThat(command.getValue().policyVersion()).isEqualTo("ADMIN-007-v7");
        then(stores).should(never()).apply(any());
        assertThat(sanction.getStatus()).isEqualTo(SanctionStatus.ACTIVE);
        assertThat(sanction.getStoreEnforcementVersion()).isEqualTo(5L);
    }
}
