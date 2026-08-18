package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorPermissionGrant;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPasswordState;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlatformOperatorAccountQueryServiceTest {
    private PlatformOperatorAccountRepository accounts;
    private PlatformOperatorRoleGrantRepository roles;
    private PlatformOperatorPermissionGrantRepository permissions;
    private PlatformOperatorAuthEventRepository authEvents;
    private OperatorAuthorityReader authorities;
    private PlatformOperatorAccountQueryService service;
    private PlatformOperatorPrincipal principal;

    @BeforeEach
    void setUp() {
        accounts = mock(PlatformOperatorAccountRepository.class);
        roles = mock(PlatformOperatorRoleGrantRepository.class);
        permissions = mock(PlatformOperatorPermissionGrantRepository.class);
        authEvents = mock(PlatformOperatorAuthEventRepository.class);
        authorities = mock(OperatorAuthorityReader.class);
        service = new PlatformOperatorAccountQueryService(accounts, roles, permissions, authEvents, authorities);
        principal = new PlatformOperatorPrincipal(1L, "caller@example.com", "secret-session", 3L, 8L, false);
    }

    @Test
    void currentUsesCentralAuthoritySnapshotInsteadOfPrincipalClaims() {
        PlatformOperatorAccount account = account(1L, "caller@example.com", "Caller");
        when(accounts.findById(1L)).thenReturn(Optional.of(account));
        when(authorities.requireCurrentAuthority(1L, 3L)).thenReturn(new OperatorAuthority(
                1L, 3L, Set.of(PlatformOperatorRole.ONBOARDING_REVIEWER),
                Set.of(PlatformOperatorPermission.ONBOARDING_REVIEW,
                        PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ,
                        PlatformOperatorPermission.AUDIT_READ)));

        var result = service.current(principal);

        assertThat(result.roles()).containsExactly(PlatformOperatorRole.ONBOARDING_REVIEWER);
        assertThat(result.permissions()).containsExactly(
                PlatformOperatorPermission.AUDIT_READ,
                PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ,
                PlatformOperatorPermission.ONBOARDING_REVIEW);
        assertThat(result.toString()).doesNotContain("secret-session", "caller@example.com");
    }

    @Test
    void detailCombinesRoleAndDirectPermissionsAndUsesOnlySuccessfulLogin() {
        PlatformOperatorAccount target = account(7L, "alice@example.com", "Alice");
        when(authorities.requireCurrentAuthority(1L, 3L)).thenReturn(manageAuthority());
        when(accounts.findById(7L)).thenReturn(Optional.of(target));
        when(roles.findAllByPlatformOperatorAccountId(7L)).thenReturn(List.of(
                PlatformOperatorRoleGrant.create(7L, PlatformOperatorRole.ONBOARDING_REVIEWER, Instant.EPOCH)));
        when(permissions.findAllByPlatformOperatorAccountId(7L)).thenReturn(List.of(
                PlatformOperatorPermissionGrant.create(7L, PlatformOperatorPermission.AUDIT_READ, Instant.EPOCH)));
        Instant loginAt = Instant.parse("2026-08-17T01:02:03Z");
        when(authEvents.findLatestOccurredAt(7L, PlatformOperatorAuthEventType.LOGIN,
                PlatformOperatorAuthEventOutcome.SUCCESS)).thenReturn(Optional.of(loginAt));

        var result = service.detail(principal, 7L);

        assertThat(result.email()).isEqualTo("al***@e******.com");
        assertThat(result.directPermissions()).containsExactly(PlatformOperatorPermission.AUDIT_READ);
        assertThat(result.effectivePermissions()).containsExactly(
                PlatformOperatorPermission.AUDIT_READ,
                PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ,
                PlatformOperatorPermission.ONBOARDING_REVIEW);
        assertThat(result.lastLoginAt()).isEqualTo(loginAt);
    }

    @Test
    void missingManagePermissionIsRejectedBeforeTargetLookup() {
        when(authorities.requireCurrentAuthority(1L, 3L)).thenReturn(new OperatorAuthority(
                1L, 3L, Set.of(PlatformOperatorRole.AUDIT_READER), Set.of(PlatformOperatorPermission.AUDIT_READ)));

        assertThatThrownBy(() -> service.detail(principal, 999L))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
        verifyNoInteractions(accounts);
    }

    @Test
    void authorizedMissingTargetUsesNonEnumerableNotFoundError() {
        when(authorities.requireCurrentAuthority(1L, 3L)).thenReturn(manageAuthority());
        when(accounts.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(principal, 999L))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.OPERATOR_ACCOUNT_NOT_FOUND));
    }

    private OperatorAuthority manageAuthority() {
        return new OperatorAuthority(1L, 3L, Set.of(PlatformOperatorRole.SUPER_ADMIN),
                Set.of(PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE));
    }

    private PlatformOperatorAccount account(long id, String email, String displayName) {
        PlatformOperatorAccount account = mock(PlatformOperatorAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getEmail()).thenReturn(email);
        when(account.getDisplayName()).thenReturn(displayName);
        when(account.getStatus()).thenReturn(PlatformOperatorAccountStatus.ACTIVE);
        when(account.getPasswordState()).thenReturn(PlatformOperatorPasswordState.ACTIVE);
        when(account.getAuthorityVersion()).thenReturn(2L);
        return account;
    }
}
