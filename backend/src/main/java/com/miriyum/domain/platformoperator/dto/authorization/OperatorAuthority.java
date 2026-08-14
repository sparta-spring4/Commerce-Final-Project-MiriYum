package com.miriyum.domain.platformoperator.dto.authorization;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.util.Set;

/** 현재 MySQL version에서 해석한 플랫폼 운영자의 역할과 세부 권한 snapshot이다. */
public record OperatorAuthority(
        long operatorId,
        long authorityVersion,
        Set<PlatformOperatorRole> roles,
        Set<PlatformOperatorPermission> permissions
) {
    public OperatorAuthority {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }
}
