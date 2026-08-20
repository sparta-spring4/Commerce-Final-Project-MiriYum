package com.miriyum.domain.platformoperator.controller.auth;

import com.miriyum.domain.platformoperator.dto.authorization.ReauthenticationApprovalRequest;
import com.miriyum.domain.platformoperator.dto.authorization.ReauthenticationApprovalResponse;
import com.miriyum.domain.platformoperator.service.ReauthenticationService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform-operators")
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorAuthorizationController {
    private final ReauthenticationService reauthentication;

    public PlatformOperatorAuthorizationController(ReauthenticationService reauthentication) {
        this.reauthentication = reauthentication;
    }

    @PostMapping("/reauthentication-approvals")
    public ApiResponse<ReauthenticationApprovalResponse> issueApproval(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @Valid @RequestBody ReauthenticationApprovalRequest request
    ) {
        return ApiResponse.success(
                "현재 비밀번호 재인증 승인을 발급했습니다.",
                ReauthenticationApprovalResponse.from(reauthentication.issue(principal, request)));
    }
}
