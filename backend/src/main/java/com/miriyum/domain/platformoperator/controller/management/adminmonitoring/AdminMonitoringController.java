package com.miriyum.domain.platformoperator.controller.management.adminmonitoring;

import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringRequests.ListQuery;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseDetail;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CasePage;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.ReconciliationStatus;
import com.miriyum.domain.platformoperator.adminmonitoring.exception.AdminMonitoringErrorCode;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringQueryService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.ApiResponse;
import java.time.Instant;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform-operators/monitoring-cases")
@ConditionalOnExpression("'${miriyum.platform-operator.enabled:false}' == 'true' and "
        + "'${miriyum.admin-monitoring.enabled:false}' == 'true'")
public class AdminMonitoringController {

    private final AdminMonitoringQueryService queries;

    public AdminMonitoringController(AdminMonitoringQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public ApiResponse<CasePage> list(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Set<CaseType> caseTypes,
            @RequestParam(required = false) Set<LifecycleStatus> lifecycleStatuses,
            @RequestParam(required = false) Set<String> sourceStatuses,
            @RequestParam(required = false) Set<ReconciliationStatus> reconciliationStatuses,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant changedFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant changedTo,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor
    ) {
        try {
            return ApiResponse.success("모니터링 사건을 조회했습니다.", queries.list(
                    principal,
                    new ListQuery(
                            storeId,
                            caseTypes,
                            lifecycleStatuses,
                            sourceStatuses,
                            reconciliationStatuses,
                            changedFrom,
                            changedTo,
                            size,
                            cursor)));
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(AdminMonitoringErrorCode.INVALID_MONITORING_FILTER);
        }
    }

    @GetMapping("/{caseType}/{caseId}")
    public ApiResponse<CaseDetail> get(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable CaseType caseType,
            @PathVariable String caseId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf
    ) {
        return ApiResponse.success(
                "모니터링 사건을 조회했습니다.",
                queries.get(principal, caseType, caseId, asOf));
    }
}
