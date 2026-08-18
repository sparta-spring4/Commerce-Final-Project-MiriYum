package com.miriyum.domain.analytics.controller.storeoperator;

import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotResponse;
import com.miriyum.domain.analytics.service.StoreDashboardAnalyticsService;
import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/store-operators/stores/{storeId}/dashboard-statistics")
@RequiredArgsConstructor
public class StoreDashboardAnalyticsController {

    private final StoreDashboardAnalyticsService analyticsService;

    @GetMapping
    public ApiResponse<DashboardSnapshotResponse> getDashboard(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId
    ) {
        return ApiResponse.success(
                "대시보드 통계를 조회했습니다.",
                analyticsService.getDashboard(principal.accountId(), storeId));
    }
}
