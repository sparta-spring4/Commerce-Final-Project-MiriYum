package com.miriyum.domain.platformoperator.dto.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.MemberStatus;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseType;
import com.miriyum.global.response.PageMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

public final class MemberSupportResponses {
    private MemberSupportResponses() {
    }

    public record MemberResponse(
            MemberAccountType accountType,
            String accountId,
            MemberStatus status,
            Instant joinedAt,
            long supportVersion,
            List<ActiveSanctionResponse> activeSanctions
    ) {
        public MemberResponse { activeSanctions = List.copyOf(activeSanctions); }
    }

    public record ActiveSanctionResponse(MemberSanctionLevel level,
                                         Set<RestrictedFeature> restrictedFeatures,
                                         OffsetDateTime endsAt) {
        public ActiveSanctionResponse { restrictedFeatures = Set.copyOf(restrictedFeatures); }
    }

    public record MemberPageResponse(List<MemberResponse> content, PageMetadata page) {
        public MemberPageResponse {
            content = List.copyOf(content);
        }
    }

    public record CaseResponse(
            String caseId, MemberSupportCaseType caseType, MemberSupportCaseStatus status,
            MemberAccountType accountType, String accountId, long version, OffsetDateTime submittedAt
    ) {
    }

    public record CasePageResponse(List<CaseResponse> content, PageMetadata page) {
        public CasePageResponse { content = List.copyOf(content); }
    }
}
