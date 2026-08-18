package com.miriyum.domain.platformoperator.dto.management;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.time.Instant;
import java.util.List;

public record PlatformOperatorAccountDetailData(
        String operatorId,
        String email,
        String displayName,
        String status,
        boolean passwordChangeRequired,
        long authorityVersion,
        List<PlatformOperatorRole> roles,
        List<PlatformOperatorPermission> directPermissions,
        List<PlatformOperatorPermission> effectivePermissions,
        Instant lastLoginAt
) {
}
