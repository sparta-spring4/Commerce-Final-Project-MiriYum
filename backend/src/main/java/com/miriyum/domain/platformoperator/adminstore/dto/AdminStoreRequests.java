package com.miriyum.domain.platformoperator.adminstore.dto;

import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;

public final class AdminStoreRequests {
    private AdminStoreRequests() {}

    public record CaseCreate(
            @NotBlank @Size(max = 50) String violationType,
            @NotEmpty @Size(max = 20) Set<@NotBlank @Size(max = 200) String> evidenceReferences,
            @NotBlank @Size(max = 50) String policyVersion) {
        public CaseCreate { evidenceReferences = evidenceReferences == null ? null : Set.copyOf(evidenceReferences); }
    }
    public record CaseAssignment(@Min(1) long expectedCaseVersion) {}
    public record SanctionShape(
            @NotNull SanctionType type,
            @NotNull Set<RestrictedFeature> restrictedFeatures,
            Instant startsAt,
            Instant endsAt) {
        public SanctionShape { restrictedFeatures = restrictedFeatures == null ? null : Set.copyOf(restrictedFeatures); }
    }
    public record ImpactConfirmation(
            @Min(1) long previewId,
            @NotBlank @Size(min = 64, max = 64) String previewDigest,
            @Min(1) long caseVersion,
            @Min(0) long storeEnforcementVersion) {}
    public record SanctionCreate(
            @NotNull SanctionType type,
            @NotNull Set<RestrictedFeature> restrictedFeatures,
            Instant startsAt,
            Instant endsAt,
            @NotBlank @Size(max = 1000) String reason,
            ImpactConfirmation impactConfirmation) {
        public SanctionCreate { restrictedFeatures = restrictedFeatures == null ? null : Set.copyOf(restrictedFeatures); }
    }
    public record SanctionApproval(@Min(1) long expectedSanctionVersion,
                                   @NotNull ImpactConfirmation impactConfirmation,
                                   @Size(max = 500) String note) {}
    public record SanctionRelease(@Min(1) long expectedSanctionVersion,
                                  @NotBlank @Size(max = 1000) String reason) {}
}
