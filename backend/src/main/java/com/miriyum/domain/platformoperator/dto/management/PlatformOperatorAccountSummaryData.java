package com.miriyum.domain.platformoperator.dto.management;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.time.Instant;
import java.util.List;

public record PlatformOperatorAccountSummaryData(
        String operatorId,
        String email,
        String displayName,
        String status,
        boolean passwordChangeRequired,
        long authorityVersion,
        List<PlatformOperatorRole> roles,
        Instant lastLoginAt
) {
}
