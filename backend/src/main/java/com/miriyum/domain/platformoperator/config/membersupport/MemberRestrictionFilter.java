package com.miriyum.domain.platformoperator.config.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.global.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberRestrictionFilter extends OncePerRequestFilter {
    private final MemberSanctionRepository sanctions;
    private final MemberRestrictionPolicy policy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MemberRestrictionFilter(MemberSanctionRepository sanctions, MemberRestrictionPolicy policy,
                                   ObjectMapper objectMapper, Clock clock) {
        this.sanctions = sanctions;
        this.policy = policy;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var feature = policy.featureFor(request.getMethod(), request.getRequestURI());
        Object rawPrincipal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (feature.isEmpty() || !(rawPrincipal instanceof AuthenticatedPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }
        MemberAccountType type = principal.namespace() == TokenNamespace.CONSUMER
                ? MemberAccountType.CONSUMER : MemberAccountType.STORE_OPERATOR;
        if (!sanctions.hasActiveFeatureRestriction(
                type, principal.accountId(), feature.get().name(), clock.instant())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(AuthErrorCode.ACCOUNT_RESTRICTED.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.from(AuthErrorCode.ACCOUNT_RESTRICTED));
    }
}
