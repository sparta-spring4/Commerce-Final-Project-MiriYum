package com.miriyum.domain.platformoperator.config;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

final class PlatformOperatorAccessDeniedHandler implements AccessDeniedHandler {
    private final ObjectMapper mapper;

    PlatformOperatorAccessDeniedHandler(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        AuthErrorCode code = authentication != null
                && authentication.getPrincipal() instanceof PlatformOperatorPrincipal principal
                && principal.passwordChangeRequired()
                ? AuthErrorCode.INITIAL_PASSWORD_CHANGE_REQUIRED
                : AuthErrorCode.FORBIDDEN;
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ErrorResponse.from(code));
    }
}
