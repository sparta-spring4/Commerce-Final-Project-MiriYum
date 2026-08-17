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

    private static final String RESERVATION_ROOT = "/api/v1/consumers/me/reservations";
    private static final String RESERVATION_FAMILY = RESERVATION_ROOT + "/**";
    private static final String RESERVATION_DETAIL = RESERVATION_ROOT + "/*";
    private static final String RESERVATION_CANCELLATION =
            RESERVATION_ROOT + "/*/cancellations";
    private static final String RESERVATION_CHECK_IN_QR_GRANT =
            RESERVATION_ROOT + "/*/check-in-qr-grants";
    private static final String RESERVATION_HISTORY =
            "/api/v1/consumers/me/reservations";
    private static final String STORE_RESERVATION_ROOT =
            "/api/v1/store-operators/stores/*/reservations";
    private static final String STORE_RESERVATION_FAMILY = STORE_RESERVATION_ROOT + "/**";
    private static final String STORE_RESERVATION_DETAIL = STORE_RESERVATION_ROOT + "/*";
    private static final String STORE_RESERVATION_CANCELLATION =
            STORE_RESERVATION_ROOT + "/*/cancellations";
    private static final String STORE_RESERVATION_FULFILLMENT =
            STORE_RESERVATION_ROOT + "/*/fulfillments";
    private static final String STORE_RESERVATION_NO_SHOW =
            STORE_RESERVATION_ROOT + "/*/no-shows";
    private static final String STORE_RESERVATION_CHECK_IN =
            "/api/v1/store-operators/stores/*/reservation-check-ins";
    private static final String WAITING_TEAM_ROOT =
            "/api/v1/store-operators/stores/*/waiting-teams";
    private static final String WAITING_TEAM_FAMILY = WAITING_TEAM_ROOT + "/**";
    private static final String WAITING_TEAM_DETAIL = WAITING_TEAM_ROOT + "/*";
    private static final String WAITING_TEAM_CALL = WAITING_TEAM_ROOT + "/*/calls";
    private static final String WAITING_TEAM_ARRIVE = WAITING_TEAM_ROOT + "/*/arrivals";
    private static final String WAITING_TEAM_CHECK_IN = WAITING_TEAM_ROOT + "/*/check-ins";
    private static final String WAITING_TEAM_CANCEL = WAITING_TEAM_ROOT + "/*/cancellations";
    private static final String WAITING_CLOSE_JOB_ROOT =
            "/api/v1/store-operators/stores/*/waiting-closure-jobs";
    private static final String WAITING_CLOSE_JOB_FAMILY = WAITING_CLOSE_JOB_ROOT + "/**";
    private static final String WAITING_CLOSE_JOB_DETAIL = WAITING_CLOSE_JOB_ROOT + "/*";
    private static final String WAITING_SETTING_ROOT =
            "/api/v1/store-operators/stores/*/waiting-settings";
    private static final String WAITING_SETTING_FAMILY = WAITING_SETTING_ROOT + "/**";
    private static final String WAITING_SETTING_DEACTIVATION_IMPACT =
            WAITING_SETTING_ROOT + "/deactivation-impact";

    @Bean
    @Order(-1)
    public SecurityFilterChain storeReservationFilterChain(
            HttpSecurity http,
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .securityMatcher(
                        STORE_RESERVATION_ROOT,
                        STORE_RESERVATION_FAMILY,
                        STORE_RESERVATION_CHECK_IN,
                        WAITING_TEAM_ROOT,
                        WAITING_TEAM_FAMILY,
                        WAITING_CLOSE_JOB_ROOT,
                        WAITING_CLOSE_JOB_FAMILY,
                        WAITING_SETTING_ROOT,
                        WAITING_SETTING_FAMILY)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, STORE_RESERVATION_ROOT).authenticated()
                        .requestMatchers(HttpMethod.GET, STORE_RESERVATION_DETAIL).authenticated()
                        .requestMatchers(HttpMethod.POST, STORE_RESERVATION_CANCELLATION).authenticated()
                        .requestMatchers(HttpMethod.POST, STORE_RESERVATION_FULFILLMENT).authenticated()
                        .requestMatchers(HttpMethod.POST, STORE_RESERVATION_NO_SHOW).authenticated()
                        .requestMatchers(HttpMethod.POST, STORE_RESERVATION_CHECK_IN).authenticated()
                        .requestMatchers(HttpMethod.GET, WAITING_TEAM_ROOT).authenticated()
                        .requestMatchers(HttpMethod.GET, WAITING_TEAM_DETAIL).authenticated()
                        .requestMatchers(HttpMethod.POST, WAITING_TEAM_CALL).authenticated()
                        .requestMatchers(HttpMethod.POST, WAITING_TEAM_ARRIVE).authenticated()
                        .requestMatchers(HttpMethod.POST, WAITING_TEAM_CHECK_IN).authenticated()
                        .requestMatchers(HttpMethod.POST, WAITING_TEAM_CANCEL).authenticated()
                        .requestMatchers(HttpMethod.GET, WAITING_CLOSE_JOB_DETAIL).authenticated()
                        .requestMatchers(HttpMethod.GET, WAITING_SETTING_ROOT).authenticated()
                        .requestMatchers(HttpMethod.PUT, WAITING_SETTING_ROOT).authenticated()
                        .requestMatchers(HttpMethod.GET, WAITING_SETTING_DEACTIVATION_IMPACT).authenticated()
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
                .securityMatcher(RESERVATION_ROOT, RESERVATION_FAMILY, RESERVATION_HISTORY)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, RESERVATION_ROOT).authenticated()
                        .requestMatchers(HttpMethod.GET, RESERVATION_HISTORY).authenticated()
                        .requestMatchers(HttpMethod.GET, RESERVATION_DETAIL).authenticated()
                        .requestMatchers(HttpMethod.POST, RESERVATION_CANCELLATION).authenticated()
                        .requestMatchers(HttpMethod.POST, RESERVATION_CHECK_IN_QR_GRANT).authenticated()
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
