package com.miriyum.global.security;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import tools.jackson.databind.ObjectMapper;
import com.miriyum.domain.auth.jwt.JwtAccessDeniedHandler;
import com.miriyum.domain.auth.jwt.JwtAuthenticationEntryPoint;
import com.miriyum.domain.auth.jwt.JwtAuthenticationFilter;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.password.Sha256BCryptPasswordEncoder;
import com.miriyum.domain.auth.ratelimit.RateLimitFilter;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 계정 namespace별로 필터체인을 분리한다. 일반 사용자·매장 운영자 보호 API는 각자 namespace의
 * Bearer Access JWT를 요구하고, 인증 진입점(가입·로그인·재발급·로그아웃)은 컨트롤러·서비스 안에서
 * 쿠키·CSRF·Origin을 자체 검증하므로 Security 단계에서는 permitAll로 통과시킨다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 비밀번호 해시 방식을 저장 값 접두사로 구분하는 {@link DelegatingPasswordEncoder}를 쓴다.
     *
     * <p>기본 방식은 {@code sha256-bcrypt}다. BCrypt는 72 UTF-8 byte를 넘는 입력을 처리하지 못하는데
     * AUTH-006은 최대 64 유니코드 코드 포인트를 허용하므로, 한글처럼 코드 포인트당 byte 수가 큰
     * 비밀번호를 그대로 BCrypt에 넘기면 정책상 유효한 값을 거부하게 된다. 자세한 이유는
     * {@link Sha256BCryptPasswordEncoder} 참고.</p>
     *
     * <p>접두사가 없는 해시는 이 방식을 도입하기 전 개발 DB에 남은 순수 BCrypt 값이므로
     * {@code setDefaultPasswordEncoderForMatches}로 계속 검증만 되게 둔다. 운영 데이터가 생기기
     * 전에 정리하고 이 fallback은 제거해야 한다.</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcryptPasswordEncoder = new BCryptPasswordEncoder();
        DelegatingPasswordEncoder passwordEncoder = new DelegatingPasswordEncoder(
                Sha256BCryptPasswordEncoder.ENCODING_ID,
                Map.of(
                        Sha256BCryptPasswordEncoder.ENCODING_ID,
                        new Sha256BCryptPasswordEncoder(bcryptPasswordEncoder),
                        "bcrypt", bcryptPasswordEncoder));
        passwordEncoder.setDefaultPasswordEncoderForMatches(bcryptPasswordEncoder);
        return passwordEncoder;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain consumerAccountFilterChain(
            HttpSecurity http,
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .securityMatcher("/api/v1/consumer-accounts/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JwtAccessDeniedHandler(objectMapper)))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, TokenNamespace.CONSUMER),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain storeOperatorAccountFilterChain(
            HttpSecurity http,
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .securityMatcher("/api/v1/store-operator-accounts/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JwtAccessDeniedHandler(objectMapper)))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, TokenNamespace.STORE_OPERATOR),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain publicAuthFilterChain(
            HttpSecurity http,
            RateLimiter rateLimiter,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .securityMatcher("/api/v1/consumer-auth/**", "/api/v1/store-operator-auth/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new RateLimitFilter(rateLimiter, objectMapper), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
        return http.build();
    }
}
