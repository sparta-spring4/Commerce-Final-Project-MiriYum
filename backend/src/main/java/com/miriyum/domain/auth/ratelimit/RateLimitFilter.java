package com.miriyum.domain.auth.ratelimit;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 가입·로그인·재발급·CSRF 준비 요청을 IP 기준으로 제한한다({@code docs/specs/auth-account/spec.md}
 * "멱등성과 요청 제한" 절). 로그아웃과 마이페이지 등 나머지 요청은 대상에서 뺀다.
 *
 * <p>{@link HttpServletRequest#getRemoteAddr()}는 리버스 프록시·로드밸런서 뒤에서 실행되면
 * 클라이언트가 아니라 프록시의 IP를 반환해, 모든 사용자가 같은 한도를 나눠 쓰게 될 수 있다.
 * 1차 MVP는 그런 프록시 구성이 확정되지 않아 이 방식을 그대로 쓰며, 실제 배포 구조가 정해지면
 * {@code X-Forwarded-For} 등 신뢰할 수 있는 헤더를 읽도록 바꿔야 한다.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_REQUESTS = Set.of(
            "POST /api/v1/consumer-auth/accounts",
            "POST /api/v1/consumer-auth/sessions",
            "POST /api/v1/consumer-auth/token-refreshes",
            "GET /api/v1/consumer-auth/csrf-tokens/current",
            "POST /api/v1/store-operator-auth/accounts",
            "POST /api/v1/store-operator-auth/sessions",
            "POST /api/v1/store-operator-auth/token-refreshes",
            "GET /api/v1/store-operator-auth/csrf-tokens/current"
    );

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestKey = request.getMethod() + " " + request.getRequestURI();
        return !LIMITED_REQUESTS.contains(requestKey);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String key = clientIp(request) + ":" + request.getMethod() + ":" + request.getRequestURI();

        if (!rateLimiter.tryConsume(key)) {
            long retryAfterSeconds = rateLimiter.retryAfterSeconds(key);
            response.setStatus(CommonErrorCode.TOO_MANY_REQUESTS.getHttpStatus().value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), ErrorResponse.from(CommonErrorCode.TOO_MANY_REQUESTS));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
