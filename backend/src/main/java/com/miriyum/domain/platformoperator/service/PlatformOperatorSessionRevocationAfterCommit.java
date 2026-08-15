package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.session.PlatformOperatorSessionManager;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorSessionRevocationAfterCommit {

    private final PlatformOperatorSessionManager sessions;

    public PlatformOperatorSessionRevocationAfterCommit(PlatformOperatorSessionManager sessions) {
        this.sessions = sessions;
    }

    public void schedule(long operatorId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("session revocation must be scheduled inside an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    sessions.revokeAll(operatorId);
                } catch (RuntimeException exception) {
                    throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
                }
            }
        });
    }
}
