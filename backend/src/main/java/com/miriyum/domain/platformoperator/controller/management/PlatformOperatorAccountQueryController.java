package com.miriyum.domain.platformoperator.controller.management;

import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountDetailData;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountPageData;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountSearchRequest;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAccountQueryService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 플랫폼 운영자 콘솔의 본인·계정·권한 읽기 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/platform-operators")
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorAccountQueryController {
    private final PlatformOperatorAccountQueryService service;

    public PlatformOperatorAccountQueryController(PlatformOperatorAccountQueryService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    public ApiResponse<PlatformOperatorAccountPageData> search(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.success("운영자 계정 목록을 조회했습니다.", service.search(
                principal, PlatformOperatorAccountSearchRequest.of(status, role, query, page, size, sort)));
    }

    @GetMapping("/accounts/{operatorId}")
    public ApiResponse<PlatformOperatorAccountDetailData> detail(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String operatorId) {
        return ApiResponse.success("운영자 계정 상세를 조회했습니다.", service.detail(principal, numericId(operatorId)));
    }

    private static long numericId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}
