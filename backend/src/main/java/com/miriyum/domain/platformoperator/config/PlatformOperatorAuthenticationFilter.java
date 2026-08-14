package com.miriyum.domain.platformoperator.config;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtAuthenticationFilter;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuthService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class PlatformOperatorAuthenticationFilter extends OncePerRequestFilter {
    private static final String BEARER = "Bearer ";
    private final PlatformOperatorAuthService service;

    public PlatformOperatorAuthenticationFilter(PlatformOperatorAuthService service) { this.service = service; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            request.setAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE, AuthErrorCode.ACCESS_TOKEN_REQUIRED);
            chain.doFilter(request, response);
            return;
        }
        try {
            PlatformOperatorPrincipal principal = service.authenticateAccess(header.substring(BEARER.length()));
            String authority = principal.passwordChangeRequired()
                    ? "ROLE_PLATFORM_OPERATOR_INITIAL_PASSWORD"
                    : "ROLE_PLATFORM_OPERATOR";
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(authority))));
        } catch (ServiceException exception) {
            request.setAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE, exception.getErrorCode());
        }
        chain.doFilter(request, response);
    }
}
