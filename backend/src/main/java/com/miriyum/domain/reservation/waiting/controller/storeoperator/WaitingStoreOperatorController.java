package com.miriyum.domain.reservation.waiting.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamListQuery;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamPage;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.service.WaitingCommandFacade;
import com.miriyum.domain.reservation.waiting.service.WaitingTeamQueryService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 대표 매장 운영자의 웨이팅 원장 조회와 전이 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/store-operators/stores/{storeId}/waiting-teams")
@RequiredArgsConstructor
public class WaitingStoreOperatorController {

    private final WaitingTeamQueryService queryService;
    private final WaitingCommandFacade commandFacade;

    /** 선택 상태와 opaque cursor로 안정적인 FIFO 목록을 조회한다. */
    @GetMapping
    public ApiResponse<WaitingTeamPage> getTeams(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        WaitingTeamListQuery query = WaitingTeamListQuery.from(status, cursor, size);
        return ApiResponse.success(
                "웨이팅 팀 목록을 조회했습니다.",
                queryService.getTeams(principal.accountId(), storeId, query));
    }

    /** 해당 매장 범위의 개인정보 안전한 팀 상세를 조회한다. */
    @GetMapping("/{waitingTeamId}")
    public ApiResponse<WaitingTeamSnapshot> getTeam(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long waitingTeamId
    ) {
        return ApiResponse.success(
                "웨이팅 팀을 조회했습니다.",
                queryService.getTeam(principal.accountId(), storeId, waitingTeamId));
    }

    /** FIFO 선두 WAITING 팀을 호출한다. */
    @PostMapping("/{waitingTeamId}/call")
    public ResponseEntity<ApiResponse<WaitingTeamSnapshot>> call(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long waitingTeamId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingTeamTransitionRequest request
    ) {
        return response(commandFacade.call(
                principal.accountId(), storeId, waitingTeamId,
                IdempotencyKey.parse(rawKey), request), "웨이팅 팀을 호출했습니다.");
    }

    /** 호출된 팀의 현장 도착을 확인한다. */
    @PostMapping("/{waitingTeamId}/arrive")
    public ResponseEntity<ApiResponse<WaitingTeamSnapshot>> arrive(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long waitingTeamId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingTeamTransitionRequest request
    ) {
        return response(commandFacade.arrive(
                principal.accountId(), storeId, waitingTeamId,
                IdempotencyKey.parse(rawKey), request), "웨이팅 팀의 도착을 확인했습니다.");
    }

    /** 도착 확인된 팀의 입장을 완료한다. */
    @PostMapping("/{waitingTeamId}/check-in")
    public ResponseEntity<ApiResponse<WaitingTeamSnapshot>> checkIn(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long waitingTeamId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingTeamTransitionRequest request
    ) {
        return response(commandFacade.checkIn(
                principal.accountId(), storeId, waitingTeamId,
                IdempotencyKey.parse(rawKey), request), "웨이팅 팀의 입장을 완료했습니다.");
    }

    /** 활성 팀을 매장 운영자 요청으로 취소한다. */
    @PostMapping("/{waitingTeamId}/cancel")
    public ResponseEntity<ApiResponse<WaitingTeamSnapshot>> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long waitingTeamId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingTeamTransitionRequest request
    ) {
        return response(commandFacade.cancel(
                principal.accountId(), storeId, waitingTeamId,
                IdempotencyKey.parse(rawKey), request), "웨이팅 팀을 취소했습니다.");
    }

    private ResponseEntity<ApiResponse<WaitingTeamSnapshot>> response(
            WaitingCommandResult result,
            String message
    ) {
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(message, result.data()));
    }
}
