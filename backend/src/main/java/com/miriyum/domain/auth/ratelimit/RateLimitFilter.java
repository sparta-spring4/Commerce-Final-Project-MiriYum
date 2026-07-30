package com.miriyum.domain.auth.ratelimit;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
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

    private static final Map<String, RateLimitCategory> LIMITED_REQUESTS = Map.ofEntries(
            Map.entry("POST /api/v1/consumer-auth/accounts", RateLimitCategory.SIGN_UP),
            Map.entry("POST /api/v1/consumer-auth/sessions", RateLimitCategory.LOGIN),
            Map.entry("POST /api/v1/consumer-auth/token-refreshes", RateLimitCategory.TOKEN_REFRESH),
            Map.entry("GET /api/v1/consumer-auth/csrf-tokens/current", RateLimitCategory.CSRF_PREPARATION),
            Map.entry("POST /api/v1/store-operator-auth/accounts", RateLimitCategory.SIGN_UP),
            Map.entry("POST /api/v1/store-operator-auth/sessions", RateLimitCategory.LOGIN),
            Map.entry("POST /api/v1/store-operator-auth/token-refreshes", RateLimitCategory.TOKEN_REFRESH),
            Map.entry("GET /api/v1/store-operator-auth/csrf-tokens/current", RateLimitCategory.CSRF_PREPARATION)
    );

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
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
        String key = clientIp(request) + ":" + requestKey(request);

        if (!rateLimiter.tryConsume(category, key)) {
            long retryAfterSeconds = rateLimiter.retryAfterSeconds(category, key);
            response.setStatus(CommonErrorCode.TOO_MANY_REQUESTS.getHttpStatus().value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), ErrorResponse.from(CommonErrorCode.TOO_MANY_REQUESTS));
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
