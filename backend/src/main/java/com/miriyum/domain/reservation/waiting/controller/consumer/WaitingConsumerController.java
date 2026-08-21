package com.miriyum.domain.reservation.waiting.controller.consumer;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerCreateRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.HistoryPage;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.HistoryQuery;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.Scope;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingReceptionAvailability;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerCommandFacade;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerHistoryQueryService;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerQueryService;
import com.miriyum.domain.reservation.waiting.service.WaitingLocationProofService;
import com.miriyum.domain.reservation.waiting.service.WaitingPartyService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** 소비자 JWT 주체의 웨이팅 등록·현재 조회·취소 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/consumers/me")
@RequiredArgsConstructor
public class WaitingConsumerController {

    private final WaitingConsumerQueryService queryService;
    private final WaitingConsumerHistoryQueryService historyQueryService;
    private final WaitingConsumerCommandFacade commandFacade;
    private final WaitingLocationProofService locationProofService;
    private final WaitingPartyService partyService;

    /**
     * 다른 소비자 식별자를 입력받지 않고 JWT 주체 본인의 현재·종료 이력만 조회한다.
     *
     * @param principal Consumer Access Token에서 검증한 인증 주체
     * @param scope 현재·종료 상태 묶음 필터
     * @param cursor 서버가 발급한 opaque cursor
     * @param size 요청 page 크기
     * @return 공개 필드만 포함한 최신 등록순 이력 page
     */
    @GetMapping("/waiting-teams")
    public ApiResponse<HistoryPage> getHistory(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) Scope scope,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer size
    ) {
        return ApiResponse.success(
                "웨이팅 이력을 조회했습니다.",
                historyQueryService.getHistory(
                        principal.accountId(), HistoryQuery.of(scope, cursor, size)));
    }

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

    @PostMapping("/waiting-teams/{teamId}/invitations")
    public ResponseEntity<ApiResponse<WaitingPartyContracts.InvitationSnapshot>> issueInvitation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long teamId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingPartyContracts.ExpectedVersionRequest request
    ) {
        var result = partyService.issueInvitation(
                principal.accountId(), teamId, IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("일행 초대를 발급했습니다.", result.data()));
    }

    @PostMapping("/waiting-teams/{teamId}/invitations/{invitationId}/revocations")
    public ResponseEntity<ApiResponse<WaitingPartyContracts.InvitationSnapshot>> revokeInvitation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long teamId,
            @PathVariable @Positive long invitationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingPartyContracts.ExpectedVersionRequest request
    ) {
        var result = partyService.revokeInvitation(
                principal.accountId(), teamId, invitationId,
                IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("일행 초대를 철회했습니다.", result.data()));
    }

    @PostMapping("/waiting-invitation-acceptances")
    public ResponseEntity<ApiResponse<WaitingConsumerSnapshot>> acceptInvitation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingPartyContracts.InvitationAcceptanceRequest request
    ) {
        var result = partyService.acceptInvitation(
                principal.accountId(), IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("일행 초대에 참여했습니다.", result.data()));
    }

    @PostMapping("/waiting-teams/{teamId}/membership-departures")
    public ResponseEntity<ApiResponse<WaitingConsumerSnapshot>> departMembership(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long teamId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingPartyContracts.ExpectedVersionRequest request
    ) {
        var result = partyService.depart(
                principal.accountId(), teamId, IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("일행에서 이탈했습니다.", result.data()));
    }

    @PostMapping("/waiting-teams/{teamId}/memberships/{membershipId}/removals")
    public ResponseEntity<ApiResponse<WaitingConsumerSnapshot>> removeMembership(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long teamId,
            @PathVariable @Positive long membershipId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingPartyContracts.ExpectedVersionRequest request
    ) {
        var result = partyService.removeMember(
                principal.accountId(), teamId, membershipId,
                IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("일행 구성원을 제거했습니다.", result.data()));
    }

    @PostMapping("/waiting-teams/{teamId}/representative-transfer-offers")
    public ResponseEntity<ApiResponse<WaitingPartyContracts.TransferOfferSnapshot>> proposeTransfer(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long teamId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingPartyContracts.TransferProposalRequest request
    ) {
        var result = partyService.proposeTransfer(
                principal.accountId(), teamId, IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("대표자 이전을 제안했습니다.", result.data()));
    }

    @PostMapping("/waiting-teams/{teamId}/representative-transfer-offers/{offerId}/acceptances")
    public ResponseEntity<ApiResponse<WaitingConsumerSnapshot>> acceptTransfer(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long teamId,
            @PathVariable @Positive long offerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingPartyContracts.ExpectedVersionRequest request
    ) {
        var result = partyService.acceptTransfer(
                principal.accountId(), teamId, offerId, IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("대표자 이전을 수락했습니다.", result.data()));
    }

    @PostMapping("/waiting-teams/{teamId}/representative-transfer-offers/{offerId}/rejections")
    public ResponseEntity<ApiResponse<WaitingPartyContracts.TransferOfferSnapshot>> rejectTransfer(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long teamId,
            @PathVariable @Positive long offerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingPartyContracts.ExpectedVersionRequest request
    ) {
        var result = partyService.rejectTransfer(
                principal.accountId(), teamId, offerId, IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("대표자 이전을 거절했습니다.", result.data()));
    }

    @PostMapping("/waiting-teams/{teamId}/representative-transfer-offers/{offerId}/revocations")
    public ResponseEntity<ApiResponse<WaitingPartyContracts.TransferOfferSnapshot>> revokeTransfer(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long teamId,
            @PathVariable @Positive long offerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingPartyContracts.ExpectedVersionRequest request
    ) {
        var result = partyService.revokeTransfer(
                principal.accountId(), teamId, offerId, IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("대표자 이전 제안을 철회했습니다.", result.data()));
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
