package com.miriyum.domain.notification.controller.consumer;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.notification.dto.response.NotificationHistoryPageResponse;
import com.miriyum.domain.notification.service.NotificationHistoryService;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 소비자 본인에게 전달 완료된 IN_APP 알림 이력만 공개한다. */
@Validated
@RestController
@RequestMapping("/api/v1/consumers/me/notifications")
public class NotificationHistoryController {

    private final NotificationHistoryService historyService;
    private final ConsumerAccountService consumerAccountService;

    public NotificationHistoryController(
            NotificationHistoryService historyService,
            ConsumerAccountService consumerAccountService
    ) {
        this.historyService = historyService;
        this.consumerAccountService = consumerAccountService;
    }

    @GetMapping
    public ApiResponse<NotificationHistoryPageResponse> getHistory(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false)
            @Size(min = 1, max = 512)
            @Pattern(regexp = "^[A-Za-z0-9_-]+$")
            String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        consumerAccountService.requireActiveAccount(principal.accountId());
        NotificationHistoryPageResponse response = historyService.getHistory(
                principal.accountId(), cursor, size);
        return ApiResponse.success("조회했습니다.", response);
    }
}
