package com.miriyum.domain.platformoperator.adminstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.CaseCreate;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionCase;
import com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionCaseRepository;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.service.StoreAdministrationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        given(authorities.requireCurrentAuthority(17L, 3L)).willReturn(new OperatorAuthority(
                17L, 3L, Set.of(), Set.of(PlatformOperatorPermission.STORE_SANCTION)));
        given(cases.save(org.mockito.ArgumentMatchers.any())).willAnswer(invocation -> invocation.getArgument(0));
        StoreSanctionCaseService service = new StoreSanctionCaseService(
                cases, stores, assignments, mock(com.miriyum.domain.platformoperator.service.AdminCaseAssignmentVerifier.class),
                authorities, CLOCK);

        var created = service.create(PRINCIPAL, 101L,
                new CaseCreate("FRAUD", Set.of("evidence://101"), "ADMIN-007-v1"));
        ArgumentCaptor<StoreSanctionCase> savedCase = ArgumentCaptor.forClass(StoreSanctionCase.class);
        then(cases).should().save(savedCase.capture());
        given(cases.findByPublicIdAndStoreIdForUpdate(created.caseId(), 101L))
                .willReturn(Optional.of(savedCase.getValue()));

        var assigned = service.assign(PRINCIPAL, 101L, created.caseId(), 1L);

        assertThat(assigned.storeId()).isEqualTo(101L);
        assertThat(assigned.caseVersion()).isEqualTo(2L);
        ArgumentCaptor<AdminCaseAssignmentCommand> command =
                ArgumentCaptor.forClass(AdminCaseAssignmentCommand.class);
        then(assignments).should().assign(command.capture());
        assertThat(command.getValue().caseType()).isEqualTo(AdminCaseType.STORE_ENFORCEMENT);
        assertThat(command.getValue().caseId()).isEqualTo(created.caseId());
        assertThat(command.getValue().caseVersion()).isEqualTo(2L);
        then(stores).should().requireStoreExists(101L);
    }
}
