package com.miriyum.domain.reservation.waiting.controller.consumer;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.global.sse.SseStreamScope;
import com.miriyum.global.sse.SseStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 소비자 본인 웨이팅과 teamsAhead의 최소 변경 신호 stream 경계다. */
@RestController
@RequestMapping("/api/v1/consumers/me/waiting-events")
@RequiredArgsConstructor
public class WaitingConsumerEventController {

    private final SseStreamService streams;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        return streams.open(
                SseStreamScope.waitingConsumer(principal.accountId()),
                lastEventId,
                principal.accessTokenExpiresAt());
    }
}
