package com.miriyum.domain.platformoperator.controller.authorization;

import com.miriyum.domain.platformoperator.dto.authorization.PlatformOperatorCapabilitiesData;
import com.miriyum.domain.platformoperator.service.PlatformOperatorCapabilitiesService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.response.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 플랫폼 운영자 콘솔이 현재 중앙 권한만 조회하는 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/platform-operators")
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorCapabilitiesController {
    private final PlatformOperatorCapabilitiesService service;

    public PlatformOperatorCapabilitiesController(PlatformOperatorCapabilitiesService service) {
        this.service = service;
    }

    /** 현재 인증된 운영자의 역할과 최종 유효 권한을 반환한다. */
    @GetMapping("/me")
    public ApiResponse<PlatformOperatorCapabilitiesData> current(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal) {
        return ApiResponse.success("현재 운영자 권한을 조회했습니다.", service.current(principal));
    }
}
