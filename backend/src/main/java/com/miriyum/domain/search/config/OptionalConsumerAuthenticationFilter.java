package com.miriyum.domain.search.config;

import com.miriyum.domain.auth.jwt.JwtAuthenticationEntryPoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 인증 헤더가 없으면 공개 검색을 허용하고, 제출된 헤더가 유효하지 않으면 거부한다. */
public class OptionalConsumerAuthenticationFilter extends OncePerRequestFilter {

    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    public OptionalConsumerAuthenticationFilter(
            JwtAuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.GET.matches(request.getMethod())
                || !"/api/v1/stores".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new InsufficientAuthenticationException("invalid optional consumer token"));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
