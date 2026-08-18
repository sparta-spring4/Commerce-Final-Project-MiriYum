package com.miriyum.domain.platformoperator.dto.authorization;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.util.List;

/** 현재 중앙 권한 version에서 해석한 플랫폼 운영자의 활성 역할과 최종 유효 권한이다. */
public record PlatformOperatorCapabilitiesData(
        long authorityVersion,
        List<PlatformOperatorRole> roles,
        List<PlatformOperatorPermission> permissions
) {
    public PlatformOperatorCapabilitiesData {
        roles = List.copyOf(roles);
        permissions = List.copyOf(permissions);
    }
}
