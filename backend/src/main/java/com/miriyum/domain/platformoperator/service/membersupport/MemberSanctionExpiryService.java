package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberSanctionExpiryService {
    private final MemberSanctionRepository sanctions;
    private final MemberAccountSupportRegistry accounts;
    private final Clock clock;

    public MemberSanctionExpiryService(MemberSanctionRepository sanctions,
                                       MemberAccountSupportRegistry accounts, Clock clock) {
        this.sanctions = sanctions;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public int expireDueSanctions() {
        LocalDateTime now = LocalDateTime.now(clock);
        var due = sanctions.findExpiredForUpdate(now);
        for (var sanction : due) {
            sanction.expire(now);
            if (sanction.getLevel() == MemberSanctionLevel.TEMPORARY_SUSPENSION
                    && !sanctions.existsOtherActiveSuspension(
                    sanction.getAccountType(), sanction.getAccountId(), sanction.getId(), now)) {
                var port = accounts.require(sanction.getAccountType());
                port.findMinimal(sanction.getAccountId()).ifPresent(snapshot ->
                        port.clearSuspension(sanction.getAccountId(), snapshot.supportVersion()));
            }
        }
        return due.size();
    }
}
