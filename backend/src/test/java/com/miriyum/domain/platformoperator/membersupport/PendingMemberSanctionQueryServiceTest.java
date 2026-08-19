package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.service.membersupport.PendingMemberSanctionQueryService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.exception.CommonErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PendingMemberSanctionQueryServiceTest {
    private static final PlatformOperatorPrincipal PRINCIPAL =
            new PlatformOperatorPrincipal(9L, "admin@example.com", "session", 2, 3, false);

    private final OperatorAuthorityReader authorities = mock(OperatorAuthorityReader.class);
    private final MemberSanctionRepository sanctions = mock(MemberSanctionRepository.class);
    private final PendingMemberSanctionQueryService service =
            new PendingMemberSanctionQueryService(authorities, sanctions);

    @Test
    void permissionIsRequiredBeforeTheSanctionLedgerIsRead() {
        when(authorities.requireCurrentAuthority(9L, 2L)).thenReturn(new OperatorAuthority(
                9L, 2L, Set.of(PlatformOperatorRole.SUPER_ADMIN), Set.of()));

        assertDeniedWithoutLedgerRead();
    }

    @Test
    void superAdminRoleIsRequiredEvenWithDirectApprovalPermission() {
        when(authorities.requireCurrentAuthority(9L, 2L)).thenReturn(new OperatorAuthority(
                9L, 2L, Set.of(),
                Set.of(PlatformOperatorPermission.ACCOUNT_PERMANENT_SANCTION_APPROVE)));

        assertDeniedWithoutLedgerRead();
    }

    @Test
    void pendingSanctionsExcludeTheCurrentProposerAndReturnTheApprovalVersion() {
        when(authorities.requireCurrentAuthority(9L, 2L)).thenReturn(approvalAuthority());
        LocalDateTime proposedAt = LocalDateTime.of(2026, 8, 19, 3, 4, 5);
        MemberSupportCase supportCase = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, 41L, 7L, "SEVERE_ABUSE", proposedAt);
        MemberSanction pending = MemberSanction.propose(
                supportCase, MemberSanctionLevel.PERMANENT_SUSPENSION, Set.of(),
                "SEVERE_ABUSE", "policy-v3", 8L, proposedAt);
        when(sanctions.findApprovalCandidates(
                eq(MemberSanctionStatus.PENDING_ADDITIONAL_APPROVAL), eq(9L),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(2);
                    return new PageImpl<>(List.of(pending), pageable, 1);
                });

        var result = service.list(PRINCIPAL, 0, 20);

        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(sanctions).findApprovalCandidates(
                eq(MemberSanctionStatus.PENDING_ADDITIONAL_APPROVAL), eq(9L), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort().stream().map(Sort.Order::getProperty))
                .containsExactly("proposedAt", "id");
        assertThat(pageable.getValue().getSort().stream().map(Sort.Order::getDirection))
                .containsOnly(Sort.Direction.DESC);
        assertThat(result.page().totalElements()).isEqualTo(1);
        assertThat(result.content()).singleElement().satisfies(response -> {
            assertThat(response.sanctionId()).isEqualTo(pending.getPublicId());
            assertThat(response.version()).isEqualTo(supportCase.getRowVersion());
            assertThat(response.accountType()).isEqualTo(MemberAccountType.CONSUMER);
            assertThat(response.accountId()).isEqualTo("41");
            assertThat(response.reasonCode()).isEqualTo("SEVERE_ABUSE");
            assertThat(response.policyVersion()).isEqualTo("policy-v3");
            assertThat(response.proposedAt()).isEqualTo(proposedAt.atOffset(ZoneOffset.UTC));
        });
    }

    @Test
    void invalidPageBoundariesAreRejectedBeforeTheSanctionLedgerIsRead() {
        when(authorities.requireCurrentAuthority(9L, 2L)).thenReturn(approvalAuthority());

        for (int[] query : List.of(new int[]{-1, 20}, new int[]{0, 0}, new int[]{0, 101})) {
            assertThatThrownBy(() -> service.list(PRINCIPAL, query[0], query[1]))
                    .isInstanceOfSatisfying(ServiceException.class,
                            error -> assertThat(error.getErrorCode())
                                    .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        }
        verifyNoInteractions(sanctions);
    }

    private OperatorAuthority approvalAuthority() {
        return new OperatorAuthority(9L, 2L, Set.of(PlatformOperatorRole.SUPER_ADMIN),
                Set.of(PlatformOperatorPermission.ACCOUNT_PERMANENT_SANCTION_APPROVE));
    }

    private void assertDeniedWithoutLedgerRead() {
        assertThatThrownBy(() -> service.list(PRINCIPAL, 0, 20))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
        verifyNoInteractions(sanctions);
    }
}
