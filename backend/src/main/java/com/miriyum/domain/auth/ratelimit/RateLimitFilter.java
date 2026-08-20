package com.miriyum.domain.auth.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 가입·로그인·재발급·CSRF 준비 요청을 IP 기준으로 제한한다({@code docs/specs/auth-account/spec.md}
 * "멱등성과 요청 제한" 절). 로그아웃과 마이페이지 등 나머지 요청은 대상에서 뺀다.
 *
 * <p>요청 경로는 등급({@link RateLimitCategory})을 고르는 데만 쓰고, 카운터 키는 IP만 사용한다.
 * 그래서 같은 IP의 일반 사용자 가입과 매장 운영자 가입처럼 같은 등급에 속한 서로 다른 namespace
 * 요청은 "IP당 N회" 한도를 함께 나눠 쓴다(합쳐서 N회). 경로별로 각각 N회를 주려는 게 아니라
 * 정책 문구("IP당 10분에 5회" 등)가 등급 단위 총량을 뜻하기 때문이다.</p>
 *
 * <p>배포 환경에서는 Nginx가 외부 요청의 forwarded IP 헤더를 원격 주소로 덮어쓰고 Spring Boot의
 * native forwarded-header 처리가 이를 {@link HttpServletRequest#getRemoteAddr()}에 반영한다. 따라서
 * 이 필터는 클라이언트가 직접 보낸 forwarded header가 아니라 신뢰 프록시 경계를 통과한 주소만 쓴다.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, RateLimitCategory> LIMITED_REQUESTS = Map.ofEntries(
            Map.entry("POST /api/v1/consumers/auth/accounts", RateLimitCategory.SIGN_UP),
            Map.entry("POST /api/v1/consumers/auth/sessions", RateLimitCategory.LOGIN),
            Map.entry("POST /api/v1/consumers/auth/kakao/authorizations", RateLimitCategory.LOGIN),
            Map.entry("POST /api/v1/consumers/auth/kakao/sessions", RateLimitCategory.LOGIN),
            Map.entry("POST /api/v1/consumers/auth/kakao/accounts", RateLimitCategory.SIGN_UP),
            Map.entry("POST /api/v1/consumers/auth/token-refreshes", RateLimitCategory.TOKEN_REFRESH),
            Map.entry("GET /api/v1/consumers/auth/csrf-tokens/current", RateLimitCategory.CSRF_PREPARATION),
            Map.entry("POST /api/v1/store-operators/auth/accounts", RateLimitCategory.SIGN_UP),
            Map.entry("POST /api/v1/store-operators/auth/sessions", RateLimitCategory.LOGIN),
            Map.entry("POST /api/v1/store-operators/auth/kakao/authorizations", RateLimitCategory.LOGIN),
            Map.entry("POST /api/v1/store-operators/auth/kakao/sessions", RateLimitCategory.LOGIN),
            Map.entry("POST /api/v1/store-operators/auth/kakao/accounts", RateLimitCategory.SIGN_UP),
            Map.entry("POST /api/v1/store-operators/auth/token-refreshes", RateLimitCategory.TOKEN_REFRESH),
            Map.entry("GET /api/v1/store-operators/auth/csrf-tokens/current", RateLimitCategory.CSRF_PREPARATION),
            Map.entry("POST /api/v1/platform-operators/auth/sessions", RateLimitCategory.LOGIN),
            Map.entry(
                    "POST /api/v1/platform-operators/auth/token-refreshes",
                    RateLimitCategory.TOKEN_REFRESH),
            Map.entry(
                    "GET /api/v1/platform-operators/auth/csrf-tokens/current",
                    RateLimitCategory.CSRF_PREPARATION)
    );

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final StagingRateLimitBypass stagingBypass;

    public RateLimitFilter(
            RateLimiter rateLimiter,
            ObjectMapper objectMapper,
            StagingRateLimitBypass stagingBypass
    ) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.stagingBypass = stagingBypass;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LIMITED_REQUESTS.containsKey(requestKey(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimitCategory category = LIMITED_REQUESTS.get(requestKey(request));
        String clientIp = clientIp(request);
        if (stagingBypass.allows(category, clientIp)) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimiter.RateLimitResult result = rateLimiter.tryConsume(category, clientIp);

        if (!result.allowed()) {
            RateLimitRejectionWriter.write(response, objectMapper, result);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String requestKey(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
