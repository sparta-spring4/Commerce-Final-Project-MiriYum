package com.miriyum.domain.analytics.config;

import com.miriyum.domain.auth.jwt.JwtAccessDeniedHandler;
import com.miriyum.domain.auth.jwt.JwtAuthenticationEntryPoint;
import com.miriyum.domain.auth.jwt.JwtAuthenticationFilter;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/** dashboard 통계 단일 경로를 store-operator namespace로 fail-closed 보호한다. */
@Configuration
@EnableWebSecurity
public class AnalyticsSecurityConfig {

    private static final String DASHBOARD_PATH =
            "/api/v1/store-operators/stores/*/dashboard-statistics";

    @Bean
    @Order(-2)
    public SecurityFilterChain analyticsFilterChain(
            HttpSecurity http,
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .securityMatcher(DASHBOARD_PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, DASHBOARD_PATH).authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JwtAccessDeniedHandler(objectMapper)))
                .addFilterBefore(
                        new JwtAuthenticationFilter(
                                jwtTokenProvider,
                                TokenNamespace.STORE_OPERATOR),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
