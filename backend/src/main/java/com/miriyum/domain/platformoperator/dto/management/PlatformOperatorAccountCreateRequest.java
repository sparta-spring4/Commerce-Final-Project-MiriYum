package com.miriyum.domain.platformoperator.dto.management;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public record PlatformOperatorAccountCreateRequest(
        @NotNull UUID provisioningId,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 50) String displayName,
        @NotBlank @Size(min = 8, max = 64) String temporaryPassword,
        @NotNull Set<PlatformOperatorRole> roles,
        @NotNull Set<PlatformOperatorPermission> directPermissions,
        @NotNull PlatformOperatorAuditReason reason
) {
    public PlatformOperatorAccountCreateRequest {
        roles = roles == null ? null : Set.copyOf(roles);
        directPermissions = directPermissions == null ? null : Set.copyOf(directPermissions);
    }

    @Override
    public String toString() {
        return "PlatformOperatorAccountCreateRequest[provisioningId=" + provisioningId
                + ", email=" + email + ", displayName=" + displayName
                + ", temporaryPassword=<redacted>, roles=" + roles
                + ", directPermissions=" + directPermissions + ", reason=" + reason + "]";
    }
}
