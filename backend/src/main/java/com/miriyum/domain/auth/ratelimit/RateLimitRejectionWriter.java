package com.miriyum.domain.auth.ratelimit;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * 요청 제한 거부를 모든 보안 필터에서 동일한 HTTP 오류 계약으로 기록한다.
 */
public final class RateLimitRejectionWriter {

    private RateLimitRejectionWriter() {
    }

    /**
     * 429 상태, 재시도 가능 시점, 그리고 공통 오류 본문을 응답에 쓴다.
     */
    public static void write(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            RateLimiter.RateLimitResult result
    ) throws IOException {
        response.setStatus(CommonErrorCode.TOO_MANY_REQUESTS.getHttpStatus().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.from(CommonErrorCode.TOO_MANY_REQUESTS));
    }
}
