package com.miriyum.domain.platformoperator.adminstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.SanctionCreate;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionStatus;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanction;
import com.miriyum.domain.platformoperator.adminstore.model.StoreSanctionPolicyCatalog;
import com.miriyum.domain.platformoperator.adminstore.repository.*;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.service.*;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.idempotency.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
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
}
