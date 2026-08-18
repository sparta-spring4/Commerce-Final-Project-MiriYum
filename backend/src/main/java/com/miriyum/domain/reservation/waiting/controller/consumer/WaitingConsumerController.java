package com.miriyum.domain.reservation.waiting.controller.consumer;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerCreateRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingReceptionAvailability;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerCommandFacade;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerQueryService;
import com.miriyum.domain.reservation.waiting.service.WaitingLocationProofService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 소비자 JWT 주체의 웨이팅 등록·현재 조회·취소 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/consumers/me")
@RequiredArgsConstructor
public class WaitingConsumerController {

    private final WaitingConsumerQueryService queryService;
    private final WaitingConsumerCommandFacade commandFacade;
    private final WaitingLocationProofService locationProofService;

    @GetMapping("/stores/{storeId}/waiting-availabilities")
    public ApiResponse<WaitingReceptionAvailability> getAvailability(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId
    ) {
        return ApiResponse.success(
                "웨이팅 접수 가능 상태를 조회했습니다.",
                queryService.getAvailability(principal.accountId(), storeId));
    }

    @PostMapping("/stores/{storeId}/waiting-location-proofs")
    public ApiResponse<WaitingLocationProofContracts.Snapshot> issueLocationProof(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @Valid @RequestBody WaitingLocationProofContracts.Request request
    ) {
        return ApiResponse.success(
                "웨이팅 위치를 판정했습니다.",
                locationProofService.issue(principal.accountId(), storeId, request));
    }

    @PostMapping("/stores/{storeId}/waiting-teams")
    public ResponseEntity<ApiResponse<WaitingConsumerSnapshot>> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingConsumerCreateRequest request
    ) {
        IdempotencyKey key = IdempotencyKey.parse(rawKey);
        WaitingConsumerCommandResult result = commandFacade.create(
                storeId,
                principal.accountId(),
                request.businessDate(),
                request.partySize(),
                request.locationProofSessionId(),
                key);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("웨이팅을 등록했습니다.", result.data()));
    }

    @GetMapping("/waiting-teams/current")
    public ApiResponse<WaitingConsumerSnapshot> getCurrent(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(
                "현재 웨이팅을 조회했습니다.",
                queryService.getCurrent(principal.accountId()));
    }

    @PostMapping("/waiting-teams/{waitingTeamId}/cancellations")
    public ResponseEntity<ApiResponse<WaitingConsumerSnapshot>> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long waitingTeamId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingTeamTransitionRequest request
    ) {
        WaitingConsumerCommandResult result = commandFacade.cancel(
                principal.accountId(), waitingTeamId, IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("웨이팅을 취소했습니다.", result.data()));
    }
}
