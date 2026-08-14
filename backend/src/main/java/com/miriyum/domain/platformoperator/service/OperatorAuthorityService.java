package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
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
    @Transactional(readOnly = true)
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

    private PlatformOperatorAccount lock(long operatorId) {
        return accounts.findByIdForUpdate(operatorId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
    }
}
