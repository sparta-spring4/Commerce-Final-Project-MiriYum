package com.miriyum.domain.notification.controller.consumer;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.notification.dto.response.NotificationHistoryPageResponse;
import com.miriyum.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.miriyum.domain.notification.service.NotificationHistoryService;
import com.miriyum.domain.notification.service.NotificationReadService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final NotificationReadService readService;

    public NotificationHistoryController(
            NotificationHistoryService historyService,
            ConsumerAccountService consumerAccountService,
            NotificationReadService readService
    ) {
        this.historyService = historyService;
        this.consumerAccountService = consumerAccountService;
        this.readService = readService;
    }

    @GetMapping
    public ApiResponse<NotificationHistoryPageResponse> getHistory(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        try {
            consumerAccountService.requireActiveAccount(principal.accountId());
        } catch (TransientDataAccessException | DataAccessResourceFailureException unavailable) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        NotificationHistoryPageResponse response = historyService.getHistory(
                principal.accountId(), cursor, size);
        return ApiResponse.success("조회했습니다.", response);
    }

    /** 활성 소비자 본인의 현재 미확인 알림 개수를 반환한다. */
    @GetMapping("/unread-count")
    public ApiResponse<NotificationUnreadCountResponse> getUnreadCount(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        requireActiveAccount(principal.accountId());
        return ApiResponse.success("조회했습니다.", readService.getUnreadCount(principal.accountId()));
    }

    /** 활성 소비자 본인의 공개 알림 하나를 멱등하게 읽음 처리한다. */
    @PostMapping("/{notificationId}/reads")
    public ApiResponse<NotificationUnreadCountResponse> readOne(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Min(1) long notificationId
    ) {
        requireActiveAccount(principal.accountId());
        return ApiResponse.success("처리했습니다.", readService.readOne(principal.accountId(), notificationId));
    }

    /** 활성 소비자 본인의 공개 미확인 알림 전체를 멱등하게 읽음 처리한다. */
    @PostMapping("/reads")
    public ApiResponse<NotificationUnreadCountResponse> readAll(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        requireActiveAccount(principal.accountId());
        return ApiResponse.success("처리했습니다.", readService.readAll(principal.accountId()));
    }

    private void requireActiveAccount(long accountId) {
        try {
            consumerAccountService.requireActiveAccount(accountId);
        } catch (TransientDataAccessException | DataAccessResourceFailureException unavailable) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
