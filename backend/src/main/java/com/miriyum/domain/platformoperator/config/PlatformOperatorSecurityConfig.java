package com.miriyum.domain.platformoperator.config;

import com.miriyum.domain.auth.jwt.JwtAuthenticationEntryPoint;
import com.miriyum.domain.auth.ratelimit.RateLimitFilter;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.auth.ratelimit.StagingRateLimitBypass;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class PlatformOperatorSecurityConfig {
    private static final String ROOT = "/api/v1/platform-operators/**";

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
    SecurityFilterChain platformOperatorPublicChain(
            HttpSecurity http,
            RateLimiter limiter,
            ObjectMapper mapper,
            StagingRateLimitBypass stagingBypass
    ) {
        http.securityMatcher(
                        "/api/v1/platform-operators/auth/sessions",
                        "/api/v1/platform-operators/auth/token-refreshes",
                        "/api/v1/platform-operators/auth/csrf-tokens/current",
                        "/api/v1/platform-operators/auth/sessions/current",
                        // There is intentionally no signup Controller; let MVC return the non-enumerable 404.
                        "/api/v1/platform-operators/auth/accounts")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                        new RateLimitFilter(limiter, mapper, stagingBypass),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
    SecurityFilterChain platformOperatorProtectedChain(
            HttpSecurity http, PlatformOperatorAuthService service, ObjectMapper mapper) {
        http.securityMatcher(ROOT)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/platform-operators/auth/initial-password")
                        .hasRole("PLATFORM_OPERATOR_INITIAL_PASSWORD")
                        .anyRequest().hasRole("PLATFORM_OPERATOR"))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(mapper))
                        .accessDeniedHandler(new PlatformOperatorAccessDeniedHandler(mapper)))
                .addFilterBefore(new PlatformOperatorAuthenticationFilter(service), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain platformOperatorDisabledChain(HttpSecurity http) {
        http.securityMatcher(ROOT)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
