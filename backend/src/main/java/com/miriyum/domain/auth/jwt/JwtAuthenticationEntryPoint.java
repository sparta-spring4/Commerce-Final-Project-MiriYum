package com.miriyum.domain.auth.jwt;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security 필터 단계에서 발생한 인증 실패를 전역 오류 응답 계약과 같은 JSON으로 내려준다.
 * {@link JwtAuthenticationFilter}가 요청 attribute에 남긴 구체 원인이 있으면 그 코드를 사용한다.
 */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        ErrorCode errorCode = resolveErrorCode(request);
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.from(errorCode));
    }

    private ErrorCode resolveErrorCode(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        return attribute instanceof ErrorCode errorCode ? errorCode : AuthErrorCode.ACCESS_TOKEN_REQUIRED;
    }
}
