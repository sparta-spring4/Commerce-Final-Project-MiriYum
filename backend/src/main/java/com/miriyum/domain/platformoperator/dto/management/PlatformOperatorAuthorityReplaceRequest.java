package com.miriyum.domain.platformoperator.dto.management;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record PlatformOperatorAuthorityReplaceRequest(
        @NotNull Set<PlatformOperatorRole> roles,
        @NotNull Set<PlatformOperatorPermission> directPermissions,
        @NotNull PlatformOperatorAuditReason reason
) {
    public PlatformOperatorAuthorityReplaceRequest {
        roles = roles == null ? null : Set.copyOf(roles);
        directPermissions = directPermissions == null ? null : Set.copyOf(directPermissions);
    }
}
