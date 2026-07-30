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
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 매장 도메인이 소유하는 catalog 공개 조회 전용 SecurityFilterChain이다.
 *
 * <p>1번 인증 도메인의 {@code SecurityConfig}("매장 운영자 필터체인은 그 도메인 구현 슬라이스에서
 * 추가한다")가 남긴 확장 지점을 사용해, 1번 파일을 수정하지 않고 이 체인 안에서 완결한다.</p>
 *
 * <p>정확히 세 GET 경로만 익명 허용하고, 같은 경로의 다른 method와 그 밖의 요청은 거부한다.
 * 인증·인가 실패는 1번의 {@link JwtAuthenticationEntryPoint}(401)·{@link JwtAccessDeniedHandler}(403)로
 * 공통 {@code ErrorResponse} envelope를 사용한다. {@code @Order(0)}으로 1번의 default {@code denyAll}
 * 체인보다 먼저 평가되며, securityMatcher가 세 경로에만 한정되어 다른 도메인 체인과 겹치지 않는다.</p>
 */
@Configuration
public class CatalogSecurityConfig {

    static final String[] PUBLIC_CATALOG_PATHS = {
            "/api/v1/store-categories",
            "/api/v1/menu-categories",
            "/api/v1/store-tags"
    };

    @Bean
    @Order(0)
    public SecurityFilterChain catalogPublicFilterChain(
            HttpSecurity http,
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .securityMatcher(PUBLIC_CATALOG_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_CATALOG_PATHS).permitAll()
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
