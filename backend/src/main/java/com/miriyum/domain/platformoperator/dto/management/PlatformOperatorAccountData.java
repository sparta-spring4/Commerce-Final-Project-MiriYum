package com.miriyum.domain.platformoperator.dto.management;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPasswordState;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.util.Set;

public record PlatformOperatorAccountData(
        String operatorId,
        String email,
        String displayName,
        String status,
        boolean passwordChangeRequired,
        long authorityVersion,
        Set<PlatformOperatorRole> roles,
        Set<PlatformOperatorPermission> directPermissions
) {
    public static PlatformOperatorAccountData from(
            PlatformOperatorAccount account,
            Set<PlatformOperatorRole> roles,
            Set<PlatformOperatorPermission> directPermissions
    ) {
        return new PlatformOperatorAccountData(
                String.valueOf(account.getId()), account.getEmail(), account.getDisplayName(),
                account.getStatus().name(), account.getPasswordState() == PlatformOperatorPasswordState.TEMPORARY,
                account.getAuthorityVersion(), Set.copyOf(roles), Set.copyOf(directPermissions));
    }
}
