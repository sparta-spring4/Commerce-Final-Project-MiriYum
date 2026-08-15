package com.miriyum.domain.platformoperator.service;

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
        lockAuthorityGuard();
        if (roles.countActiveSuperAdministratorById(targetOperatorId) == 1) {
            throw new ServiceException(AdminAuthorizationErrorCode.LAST_SUPER_ADMIN_REQUIRED);
        }
    }

    /** 운영자 관리 명령의 행위자가 유일한 활성 슈퍼관리자인지 잠금 아래 확인한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireSingletonActor(long actorOperatorId) {
        lockAuthorityGuard();
        if (roles.countActiveSuperAdministrators() != 1) {
            throw new ServiceException(AdminAuthorizationErrorCode.LAST_SUPER_ADMIN_REQUIRED);
        }
        if (roles.countActiveSuperAdministratorById(actorOperatorId) != 1) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }

    private void lockAuthorityGuard() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("super-admin decision requires an active mutation transaction");
        }
        guard.lockSingleton().orElseThrow(() -> new IllegalStateException("authority guard row is missing"));
    }
}
