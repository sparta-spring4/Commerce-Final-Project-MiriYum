package com.miriyum.domain.search.config;

import com.miriyum.domain.auth.ratelimit.RateLimitCategory;
import com.miriyum.domain.auth.ratelimit.RateLimitRejectionWriter;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 공개 매장 목록·상세·메뉴 GET 요청을 하나의 {@link RateLimitCategory#PUBLIC_STORE_READ}
 * 총량으로 제한하고, 한도 초과는 컨트롤러에 도달하기 전에 거부한다.
 *
 * <p>카운터 키는 {@link HttpServletRequest#getRemoteAddr()}를 사용한다. 이는 현재 신뢰 경계가
 * 애플리케이션 직접 연결임을 전제하므로, 신뢰할 수 있는 프록시를 도입하면 전달 헤더 처리 정책을
 * 별도로 확정해야 한다.</p>
 */
public class StoreSearchRateLimitFilter extends OncePerRequestFilter {

    private static final Pattern STORE_LIST = Pattern.compile("^/api/v1/stores$");
    private static final Pattern STORE_DETAIL = Pattern.compile("^/api/v1/stores/[^/]+$");
    private static final Pattern STORE_MENUS = Pattern.compile("^/api/v1/stores/[^/]+/menus$");
    private static final Pattern MENU_HOLD_AVAILABILITY =
            Pattern.compile("^/api/v1/stores/[^/]+/menu-hold-availability$");
    private static final Pattern MENU_ALTERNATIVE_SEARCH = Pattern.compile(
            "^/api/v1/stores/[^/]+/menus/[^/]+/alternatives/search$");

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public StoreSearchRateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        boolean publicGet = HttpMethod.GET.matches(request.getMethod())
                && (STORE_LIST.matcher(requestUri).matches()
                || STORE_DETAIL.matcher(requestUri).matches()
                || STORE_MENUS.matcher(requestUri).matches()
                || MENU_HOLD_AVAILABILITY.matcher(requestUri).matches());
        boolean alternativePost = HttpMethod.POST.matches(request.getMethod())
                && MENU_ALTERNATIVE_SEARCH.matcher(requestUri).matches();
        return !publicGet && !alternativePost;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimiter.RateLimitResult result = rateLimiter.tryConsume(
                RateLimitCategory.PUBLIC_STORE_READ,
                request.getRemoteAddr());
        if (!result.allowed()) {
            RateLimitRejectionWriter.write(response, objectMapper, result);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
