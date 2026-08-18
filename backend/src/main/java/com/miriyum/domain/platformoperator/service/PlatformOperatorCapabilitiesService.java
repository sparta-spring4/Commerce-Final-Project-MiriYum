package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.authorization.PlatformOperatorCapabilitiesData;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 세션 운영자의 중앙 역할과 최종 유효 권한만 반환하는 읽기 유스케이스다. */
@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorCapabilitiesService {
    private final OperatorAuthorityReader authorities;

    public PlatformOperatorCapabilitiesService(OperatorAuthorityReader authorities) {
        this.authorities = authorities;
    }

    /** principal의 권한 version과 현재 중앙 version이 일치하는 capabilities를 반환한다. */
    @Transactional(readOnly = true)
    public PlatformOperatorCapabilitiesData current(PlatformOperatorPrincipal principal) {
        var authority = authorities.requireCurrentAuthority(principal.accountId(), principal.authorityVersion());
        return new PlatformOperatorCapabilitiesData(
                authority.authorityVersion(), sorted(authority.roles()), sorted(authority.permissions()));
    }

    private static <E extends Enum<E>> List<E> sorted(Collection<E> values) {
        return values.stream().sorted(Comparator.comparing(Enum::name)).toList();
    }
}
