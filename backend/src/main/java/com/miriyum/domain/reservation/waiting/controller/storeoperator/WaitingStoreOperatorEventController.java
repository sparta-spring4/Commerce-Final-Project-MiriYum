package com.miriyum.domain.reservation.waiting.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.global.sse.SseStreamScope;
import com.miriyum.global.sse.SseStreamService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 대표 운영 권한을 재검증하는 매장 단위 웨이팅 변경 신호 stream 경계다. */
@Validated
@RestController
@RequestMapping("/api/v1/store-operators/stores/{storeId}/waiting-events")
@RequiredArgsConstructor
public class WaitingStoreOperatorEventController {

    private final SseStreamService streams;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        return streams.open(
                SseStreamScope.waitingStoreOperator(principal.accountId(), storeId),
                lastEventId,
                principal.accessTokenExpiresAt());
    }
}
