package com.miriyum.domain.store.search.config;

import com.miriyum.domain.auth.ratelimit.RateLimiter;
import tools.jackson.databind.ObjectMapper;
import com.miriyum.domain.auth.jwt.JwtAccessDeniedHandler;
import com.miriyum.domain.auth.jwt.JwtAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class StoreSearchSecurityConfig {

    @Bean
    @Order(-10)
    public SecurityFilterChain storeSearchFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            RateLimiter rateLimiter
    ) throws Exception {
        http
                .securityMatcher("/api/v1/stores", "/api/v1/stores/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JwtAccessDeniedHandler(objectMapper)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/stores",
                                "/api/v1/stores/{storeId}",
                                "/api/v1/stores/{storeId}/menus")
                        .permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(
                        new StoreSearchRateLimitFilter(rateLimiter, objectMapper),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
