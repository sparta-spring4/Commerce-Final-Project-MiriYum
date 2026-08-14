package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthorityGuardRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.global.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class LastSuperAdminPolicy {
    private final PlatformOperatorAuthorityGuardRepository guard;
    private final PlatformOperatorRoleGrantRepository roles;

    public LastSuperAdminPolicy(
            PlatformOperatorAuthorityGuardRepository guard,
            PlatformOperatorRoleGrantRepository roles
    ) {
        this.guard = guard;
        this.roles = roles;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertRemovable(long targetOperatorId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("last-super-admin decision requires an active mutation transaction");
        }
        guard.lockSingleton().orElseThrow(() -> new IllegalStateException("authority guard row is missing"));
        if (!roles.existsByPlatformOperatorAccountIdAndRole(targetOperatorId, PlatformOperatorRole.SUPER_ADMIN)) {
            return;
        }
        if (roles.countActiveSuperAdministrators() <= 1) {
            throw new ServiceException(AdminAuthorizationErrorCode.LAST_SUPER_ADMIN_REQUIRED);
        }
    }
}
