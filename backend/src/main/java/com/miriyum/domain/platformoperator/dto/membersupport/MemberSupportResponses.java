package com.miriyum.domain.platformoperator.dto.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.MemberStatus;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseType;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionStatus;
import com.miriyum.global.response.PageMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    public record PendingSanctionApprovalResponse(
            String sanctionId,
            long version,
            MemberAccountType accountType,
            String accountId,
            String reasonCode,
            String policyVersion,
            OffsetDateTime proposedAt
    ) {
    }

    public record PendingSanctionApprovalPageResponse(
            List<PendingSanctionApprovalResponse> content,
            PageMetadata page
    ) {
        public PendingSanctionApprovalPageResponse {
            content = List.copyOf(content);
        }
    }

    /** OpenAPI의 Sanction schema와 일치하는 명령 결과 snapshot. */
    public record SanctionResponse(
            String sanctionId,
            MemberAccountType accountType,
            String accountId,
            MemberSanctionLevel level,
            MemberSanctionStatus status,
            String policyVersion,
            long version,
            Set<RestrictedFeature> restrictedFeatures,
            OffsetDateTime proposedAt,
            OffsetDateTime appliedAt,
            OffsetDateTime endsAt
    ) {
        public SanctionResponse {
            restrictedFeatures = Set.copyOf(restrictedFeatures);
        }

        public static SanctionResponse from(MemberSanction sanction) {
            return new SanctionResponse(
                    sanction.getPublicId(),
                    sanction.getAccountType(),
                    Long.toString(sanction.getAccountId()),
                    sanction.getLevel(),
                    sanction.getStatus(),
                    sanction.getPolicyVersion(),
                    sanction.getRowVersion(),
                    sanction.restrictedFeatures(),
                    sanction.getProposedAt().atOffset(ZoneOffset.UTC),
                    sanction.getAppliedAt() == null ? null : sanction.getAppliedAt().atOffset(ZoneOffset.UTC),
                    sanction.getEndsAt() == null ? null : sanction.getEndsAt().atOffset(ZoneOffset.UTC));
        }
    }
}
