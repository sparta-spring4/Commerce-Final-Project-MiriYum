package com.miriyum.domain.reservation.config;

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

/** 소비자 일반 예약 경로군을 소유하는 fail-closed 보안 체인이다. */
@Configuration
@EnableWebSecurity
public class ReservationSecurityConfig {

    private static final String RESERVATION_ROOT = "/api/v1/reservations";
    private static final String RESERVATION_FAMILY = RESERVATION_ROOT + "/**";
    private static final String RESERVATION_DETAIL = RESERVATION_ROOT + "/*";

    /**
     * 소비자 namespace의 단일 예약 상세 GET만 인증 후 허용하고 같은 경로군의 나머지는 거부한다.
     *
     * @param http Spring Security 설정 경계
     * @param jwtTokenProvider Access JWT 검증기
     * @param objectMapper 공통 보안 오류 응답 직렬화기
     * @return 소비자 예약 경로 전용 보안 체인
     * @throws Exception 보안 체인을 구성할 수 없는 경우
     */
    @Bean
    @Order(0)
    public SecurityFilterChain reservationFilterChain(
            HttpSecurity http,
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .securityMatcher(RESERVATION_ROOT, RESERVATION_FAMILY)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, RESERVATION_DETAIL).authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JwtAccessDeniedHandler(objectMapper)))
                .addFilterBefore(
                        new JwtAuthenticationFilter(
                                jwtTokenProvider,
                                TokenNamespace.CONSUMER),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
