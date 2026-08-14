package com.miriyum.domain.platformoperator.service;

import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.AUDIT_READ;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.ONBOARDING_REVIEW;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorRole.ONBOARDING_REVIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorPermissionGrant;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OperatorAuthorityServiceTest {
    private PlatformOperatorAccountRepository accounts;
    private PlatformOperatorRoleGrantRepository roles;
    private PlatformOperatorPermissionGrantRepository permissions;
    private LastSuperAdminPolicy lastSuperAdminPolicy;
    private OperatorAuthorityService service;

    @BeforeEach
    void setUp() {
        accounts = mock(PlatformOperatorAccountRepository.class);
        roles = mock(PlatformOperatorRoleGrantRepository.class);
        permissions = mock(PlatformOperatorPermissionGrantRepository.class);
        lastSuperAdminPolicy = mock(LastSuperAdminPolicy.class);
        service = new OperatorAuthorityService(accounts, roles, permissions, lastSuperAdminPolicy,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("현재 역할의 권한과 직접 권한을 합쳐 현재 authority version과 반환한다")
    void aggregatesRoleAndDirectPermissions() {
        PlatformOperatorAccount account = mock(PlatformOperatorAccount.class);
        when(account.getId()).thenReturn(7L);
        when(account.getStatus()).thenReturn(PlatformOperatorAccountStatus.ACTIVE);
        when(account.getAuthorityVersion()).thenReturn(3L);
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(roles.findAllByPlatformOperatorAccountId(7L))
                .thenReturn(List.of(PlatformOperatorRoleGrant.create(7L, ONBOARDING_REVIEWER, Instant.EPOCH)));
        when(permissions.findAllByPlatformOperatorAccountId(7L))
                .thenReturn(List.of(PlatformOperatorPermissionGrant.create(7L, AUDIT_READ, Instant.EPOCH)));

        OperatorAuthority authority = service.currentAuthority(7L);

        assertThat(authority.operatorId()).isEqualTo(7L);
        assertThat(authority.authorityVersion()).isEqualTo(3L);
        assertThat(authority.roles()).containsExactly(ONBOARDING_REVIEWER);
        assertThat(authority.permissions())
                .containsExactlyInAnyOrder(ONBOARDING_REVIEW, ONBOARDING_EVIDENCE_READ, AUDIT_READ);
    }

    @Test
    @DisplayName("역할 회수는 계정을 잠그고 실제 grant가 삭제될 때 권한 version을 증가시킨다")
    void revokesRoleUnderAccountLockAndAdvancesVersion() {
        PlatformOperatorAccount account = mock(PlatformOperatorAccount.class);
        when(accounts.findByIdForUpdate(7L)).thenReturn(Optional.of(account));
        when(roles.deleteByPlatformOperatorAccountIdAndRole(7L, ONBOARDING_REVIEWER)).thenReturn(1L);

        service.revokeRole(7L, ONBOARDING_REVIEWER);

        verify(accounts).findByIdForUpdate(7L);
        verify(roles).deleteByPlatformOperatorAccountIdAndRole(7L, ONBOARDING_REVIEWER);
        verify(account).advanceAuthorityVersion();
    }

    @Test
    @DisplayName("요청 version이 현재 권한 version과 다르면 기존 세션 무효 오류로 거부한다")
    void rejectsStaleAuthorityVersion() {
        PlatformOperatorAccount account = mock(PlatformOperatorAccount.class);
        when(account.getId()).thenReturn(7L);
        when(account.getStatus()).thenReturn(PlatformOperatorAccountStatus.ACTIVE);
        when(account.getAuthorityVersion()).thenReturn(4L);
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(roles.findAllByPlatformOperatorAccountId(7L)).thenReturn(List.of());
        when(permissions.findAllByPlatformOperatorAccountId(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.requireCurrentAuthority(7L, 3L))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
    }
}
