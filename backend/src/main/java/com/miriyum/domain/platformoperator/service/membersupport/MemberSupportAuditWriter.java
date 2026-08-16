package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportAudit;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportAuditRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class MemberSupportAuditWriter {
    private final MemberSupportAuditRepository audits;
    private final Clock clock;

    public MemberSupportAuditWriter(MemberSupportAuditRepository audits, Clock clock) {
        this.audits = audits;
        this.clock = clock;
    }

    public MemberSupportAudit recovery(AdminAuditContext context, String decisionCode) {
        LocalDateTime now = LocalDateTime.now(clock);
        return audits.save(MemberSupportAudit.record(
                context, decisionCode, null, now, now.plusYears(3)));
    }

    public MemberSupportAudit enforcement(AdminAuditContext context, String decisionCode, String policyVersion) {
        LocalDateTime now = LocalDateTime.now(clock);
        return audits.save(MemberSupportAudit.record(context, decisionCode, policyVersion, now, null));
    }
}
