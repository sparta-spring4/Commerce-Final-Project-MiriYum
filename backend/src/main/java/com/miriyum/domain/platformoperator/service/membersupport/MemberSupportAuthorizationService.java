package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberSupportAuthorizationService {
    private final OperatorAuthorityReader authorities;

    public MemberSupportAuthorizationService(OperatorAuthorityReader authorities) {
        this.authorities = authorities;
    }

    public void requirePermission(PlatformOperatorPrincipal principal, PlatformOperatorPermission permission) {
        var authority = authorities.requireCurrentAuthority(principal.accountId(), principal.authorityVersion());
        if (!authority.permissions().contains(permission)) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }
}
