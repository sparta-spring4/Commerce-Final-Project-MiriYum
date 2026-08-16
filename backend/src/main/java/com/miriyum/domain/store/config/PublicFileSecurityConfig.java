package com.miriyum.domain.store.config;

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

/** 공개 파일 경로에서는 GET만 익명 허용하고 나머지 method는 공통 오류 형식으로 거부한다. */
@Configuration
@EnableWebSecurity
public class PublicFileSecurityConfig {

    private static final String ROOT = "/api/v1/public-files";
    private static final String FAMILY = ROOT + "/**";

    @Bean
    @Order(0)
    public SecurityFilterChain publicFileFilterChain(
            HttpSecurity http,
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .securityMatcher(ROOT, FAMILY)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, FAMILY).permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JwtAccessDeniedHandler(objectMapper)))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, TokenNamespace.CONSUMER),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
