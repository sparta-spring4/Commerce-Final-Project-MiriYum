package com.miriyum.domain.platformoperator.dto.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberStatus;
import java.time.Instant;
import java.util.List;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseType;
import java.time.LocalDateTime;

public final class MemberSupportResponses {
    private MemberSupportResponses() {
    }

    public record MemberResponse(
            MemberAccountType accountType,
            long accountId,
            MemberStatus status,
            Instant joinedAt,
            long supportVersion
    ) {
    }

    public record MemberPageResponse(List<MemberResponse> content, long totalElements, int page, int size) {
        public MemberPageResponse {
            content = List.copyOf(content);
        }
    }

    public record CaseResponse(
            String caseId, MemberSupportCaseType caseType, MemberSupportCaseStatus status,
            MemberAccountType accountType, long accountId, long targetSupportVersion,
            long version, LocalDateTime submittedAt, String decisionCode
    ) {
    }

    public record CasePageResponse(List<CaseResponse> content, long totalElements, int page, int size) {
        public CasePageResponse { content = List.copyOf(content); }
    }
}
