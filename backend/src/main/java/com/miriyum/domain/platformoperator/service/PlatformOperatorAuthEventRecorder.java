package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuthEvent;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventType;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorAuthEventRecorder {
    private final PlatformOperatorAuthEventRepository events;
    private final Clock clock;

    public PlatformOperatorAuthEventRecorder(PlatformOperatorAuthEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(PlatformOperatorAccount account, PlatformOperatorAuthEventType type,
            PlatformOperatorAuthEventOutcome outcome) {
        record(account.getId(), account.getAuthorityVersion(), account.getSessionVersion(), type, outcome);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long accountId, long authorityVersion, long sessionVersion,
            PlatformOperatorAuthEventType type, PlatformOperatorAuthEventOutcome outcome) {
        events.saveAndFlush(PlatformOperatorAuthEvent.record(accountId, type, authorityVersion, sessionVersion,
                outcome, UUID.randomUUID().toString(), clock.instant()));
    }
}
