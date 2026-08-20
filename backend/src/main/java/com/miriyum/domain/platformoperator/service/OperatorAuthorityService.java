package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL의 역할·직접 권한 grant를 현재 account version과 함께 해석한다. */
@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class OperatorAuthorityService implements OperatorAuthorityReader {
    private static final Set<PlatformOperatorPermission> ASSIGNABLE_DIRECT_PERMISSIONS = Set.of(
            PlatformOperatorPermission.ONBOARDING_REVIEW,
            PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ,
            PlatformOperatorPermission.MEMBER_READ_MINIMAL,
            PlatformOperatorPermission.MEMBER_RECOVERY,
            PlatformOperatorPermission.ACCOUNT_SANCTION,
            PlatformOperatorPermission.ACCOUNT_APPEAL_REVIEW,
            PlatformOperatorPermission.STORE_READ_MINIMAL,
            PlatformOperatorPermission.STORE_SANCTION,
            PlatformOperatorPermission.OPERATIONS_MONITOR_READ,
            PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE,
            PlatformOperatorPermission.AUDIT_READ,
            PlatformOperatorPermission.INCIDENT_RESPOND);
    private final PlatformOperatorAccountRepository accounts;
    private final PlatformOperatorRoleGrantRepository roles;
    private final PlatformOperatorPermissionGrantRepository permissions;
    private final LastSuperAdminPolicy lastSuperAdminPolicy;
    private final Clock clock;

    public OperatorAuthorityService(
            PlatformOperatorAccountRepository accounts,
            PlatformOperatorRoleGrantRepository roles,
            PlatformOperatorPermissionGrantRepository permissions,
            LastSuperAdminPolicy lastSuperAdminPolicy,
            Clock clock
    ) {
        this.accounts = accounts;
        this.roles = roles;
        this.permissions = permissions;
        this.lastSuperAdminPolicy = lastSuperAdminPolicy;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public OperatorAuthority currentAuthority(long operatorId) {
        PlatformOperatorAccount account = accounts.findById(operatorId)
                .filter(candidate -> candidate.getStatus() == PlatformOperatorAccountStatus.ACTIVE)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
        Set<PlatformOperatorRole> currentRoles = EnumSet.noneOf(PlatformOperatorRole.class);
        roles.findAllByPlatformOperatorAccountId(operatorId)
                .forEach(grant -> currentRoles.add(grant.getRole()));
        Set<PlatformOperatorPermission> currentPermissions = EnumSet.noneOf(PlatformOperatorPermission.class);
        currentRoles.forEach(role -> currentPermissions.addAll(role.permissions()));
        permissions.findAllByPlatformOperatorAccountId(operatorId)
                .forEach(grant -> currentPermissions.add(grant.getPermission()));
        return new OperatorAuthority(
                operatorId,
                account.getAuthorityVersion(),
                currentRoles,
                currentPermissions);
    }

    /** 요청 principal의 권한 version이 현재 중앙 version과 정확히 일치하는 snapshot만 반환한다. */
    @Override
    @Transactional(readOnly = true, noRollbackFor = ServiceException.class)
    public OperatorAuthority requireCurrentAuthority(long operatorId, long expectedAuthorityVersion) {
        OperatorAuthority authority = currentAuthority(operatorId);
        if (authority.authorityVersion() != expectedAuthorityVersion) {
            throw new ServiceException(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID);
        }
        return authority;
    }

    /** 계정 행을 잠근 뒤 역할을 회수하고 현재 권한·세션 version을 함께 증가시킨다. */
    @Transactional
    public void revokeRole(long operatorId, PlatformOperatorRole role) {
        PlatformOperatorAccount account = lock(operatorId);
        if (role == PlatformOperatorRole.SUPER_ADMIN) {
            lastSuperAdminPolicy.assertRemovable(operatorId);
        }
        if (roles.deleteByPlatformOperatorAccountIdAndRole(operatorId, role) == 1L) {
            account.advanceAuthorityVersion();
        }
    }

    @Transactional
    public void grantRole(long operatorId, PlatformOperatorRole role) {
        PlatformOperatorAccount account = lock(operatorId);
        if (!roles.existsByPlatformOperatorAccountIdAndRole(operatorId, role)) {
            roles.save(com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant.create(
                    operatorId, role, clock.instant()));
            account.advanceAuthorityVersion();
        }
    }

    @Transactional
    public void grantPermission(long operatorId, PlatformOperatorPermission permission) {
        PlatformOperatorAccount account = lock(operatorId);
        if (!permissions.existsByPlatformOperatorAccountIdAndPermission(operatorId, permission)) {
            permissions.save(com.miriyum.domain.platformoperator.entity.PlatformOperatorPermissionGrant.create(
                    operatorId, permission, clock.instant()));
            account.advanceAuthorityVersion();
        }
    }

    @Transactional
    public void revokePermission(long operatorId, PlatformOperatorPermission permission) {
        PlatformOperatorAccount account = lock(operatorId);
        if (permissions.deleteByPlatformOperatorAccountIdAndPermission(operatorId, permission) == 1L) {
            account.advanceAuthorityVersion();
        }
    }

    /** 하위 운영자의 역할·직접 권한을 요청한 최종 상태로 원자적으로 교체한다. */
    @Transactional
    public AuthorityReplacement replaceAuthority(
            long operatorId,
            long expectedAuthorityVersion,
            Set<PlatformOperatorRole> desiredRoles,
            Set<PlatformOperatorPermission> desiredPermissions
    ) {
        Set<PlatformOperatorRole> afterRoles = immutableRoles(desiredRoles);
        Set<PlatformOperatorPermission> afterPermissions = immutablePermissions(desiredPermissions);
        validateAssignable(afterRoles, afterPermissions);

        PlatformOperatorAccount account = lock(operatorId);
        if (account.getAuthorityVersion() != expectedAuthorityVersion) {
            throw new ServiceException(com.miriyum.global.exception.CommonErrorCode.CONCURRENT_MODIFICATION);
        }
        lastSuperAdminPolicy.assertRemovable(operatorId);

        Set<PlatformOperatorRole> beforeRoles = EnumSet.noneOf(PlatformOperatorRole.class);
        roles.findAllByPlatformOperatorAccountId(operatorId)
                .forEach(grant -> beforeRoles.add(grant.getRole()));
        Set<PlatformOperatorPermission> beforePermissions = EnumSet.noneOf(PlatformOperatorPermission.class);
        permissions.findAllByPlatformOperatorAccountId(operatorId)
                .forEach(grant -> beforePermissions.add(grant.getPermission()));

        if (beforeRoles.equals(afterRoles) && beforePermissions.equals(afterPermissions)) {
            return new AuthorityReplacement(false, beforeRoles, afterRoles, beforePermissions, afterPermissions);
        }

        beforeRoles.stream().filter(role -> !afterRoles.contains(role))
                .forEach(role -> roles.deleteByPlatformOperatorAccountIdAndRole(operatorId, role));
        afterRoles.stream().filter(role -> !beforeRoles.contains(role))
                .forEach(role -> roles.save(com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant.create(
                        operatorId, role, clock.instant())));
        beforePermissions.stream().filter(permission -> !afterPermissions.contains(permission))
                .forEach(permission -> permissions.deleteByPlatformOperatorAccountIdAndPermission(
                        operatorId, permission));
        afterPermissions.stream().filter(permission -> !beforePermissions.contains(permission))
                .forEach(permission -> permissions.save(
                        com.miriyum.domain.platformoperator.entity.PlatformOperatorPermissionGrant.create(
                                operatorId, permission, clock.instant())));
        account.advanceAuthorityVersion();
        return new AuthorityReplacement(true, beforeRoles, afterRoles, beforePermissions, afterPermissions);
    }

    private static Set<PlatformOperatorRole> immutableRoles(Set<PlatformOperatorRole> roles) {
        if (roles == null || roles.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ServiceException(AdminAuthorizationErrorCode.FORBIDDEN_AUTHORITY);
        }
        return Set.copyOf(roles);
    }

    private static Set<PlatformOperatorPermission> immutablePermissions(
            Set<PlatformOperatorPermission> permissions) {
        if (permissions == null || permissions.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ServiceException(AdminAuthorizationErrorCode.FORBIDDEN_AUTHORITY);
        }
        return Set.copyOf(permissions);
    }

    static void validateAssignable(
            Set<PlatformOperatorRole> roles,
            Set<PlatformOperatorPermission> permissions
    ) {
        if (roles.contains(PlatformOperatorRole.SUPER_ADMIN)
                || !ASSIGNABLE_DIRECT_PERMISSIONS.containsAll(permissions)) {
            throw new ServiceException(AdminAuthorizationErrorCode.FORBIDDEN_AUTHORITY);
        }
    }

    public record AuthorityReplacement(
            boolean changed,
            Set<PlatformOperatorRole> beforeRoles,
            Set<PlatformOperatorRole> afterRoles,
            Set<PlatformOperatorPermission> beforePermissions,
            Set<PlatformOperatorPermission> afterPermissions
    ) {
        public AuthorityReplacement {
            beforeRoles = Set.copyOf(beforeRoles);
            afterRoles = Set.copyOf(afterRoles);
            beforePermissions = Set.copyOf(beforePermissions);
            afterPermissions = Set.copyOf(afterPermissions);
        }
    }

    private PlatformOperatorAccount lock(long operatorId) {
        return accounts.findByIdForUpdate(operatorId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
    }
}
