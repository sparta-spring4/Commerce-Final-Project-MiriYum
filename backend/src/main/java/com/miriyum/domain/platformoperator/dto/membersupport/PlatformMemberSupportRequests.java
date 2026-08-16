package com.miriyum.domain.platformoperator.dto.membersupport;

import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberAppealOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

public final class PlatformMemberSupportRequests {
    private PlatformMemberSupportRequests() {
    }

    public record CaseDecisionRequest(
            @NotBlank String decision,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Z0-9_]+$") String reasonCode,
            MemberSanctionLevel reducedLevel,
            Set<RestrictedFeature> restrictedFeatures
    ) {
        public CaseDecisionRequest { restrictedFeatures = restrictedFeatures == null ? Set.of() : Set.copyOf(restrictedFeatures); }
    }

    public record SanctionRequest(
            @NotNull MemberSanctionLevel level,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Z0-9_]+$") String reasonCode,
            @NotBlank @Size(max = 50) String policyVersion,
            Set<RestrictedFeature> restrictedFeatures
    ) {
        public SanctionRequest { restrictedFeatures = restrictedFeatures == null ? Set.of() : Set.copyOf(restrictedFeatures); }
    }

    public record AdditionalApprovalRequest(
            @NotBlank @Pattern(regexp = "APPROVE") String decision,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Z0-9_]+$") String reasonCode
    ) {
    }
}
