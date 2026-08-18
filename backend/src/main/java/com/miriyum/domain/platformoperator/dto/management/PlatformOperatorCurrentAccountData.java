package com.miriyum.domain.platformoperator.dto.management;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.util.List;

public record PlatformOperatorCurrentAccountData(
        String operatorId,
        String displayName,
        String status,
        long authorityVersion,
        List<PlatformOperatorRole> roles,
        List<PlatformOperatorPermission> permissions,
        boolean passwordChangeRequired
) {
}
