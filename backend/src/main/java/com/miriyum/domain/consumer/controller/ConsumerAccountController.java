package com.miriyum.domain.consumer.controller;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.consumer.dto.request.ConsumerAccountUpdateRequest;
import com.miriyum.domain.consumer.dto.response.ConsumerAccountResponse;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 일반 사용자 본인 정보 조회·수정과 예약 내역 조회를 제공하는 마이페이지 HTTP 경계다.
 * 예약 조회 규칙과 응답 DTO는 예약 도메인의 공개 {@link ReservationService} 계약을 사용한다.
 */
@RestController
@RequestMapping("/api/v1/consumer-accounts")
@RequiredArgsConstructor
public class ConsumerAccountController {

    private static final String UPDATE_COMMAND_TYPE = "CONSUMER_ACCOUNT_UPDATE";
    private static final String UPDATE_ROUTE = "PATCH /api/v1/consumer-accounts/me";

    private final ConsumerAccountService consumerAccountService;
    private final ReservationService reservationService;

    @GetMapping("/me")
    public ApiResponse<ConsumerAccountResponse> getMe(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success("조회했습니다.", consumerAccountService.getMe(principal.accountId()));
    }

    /**
     * 인증된 소비자의 계정 ID를 예약 도메인 공개 조회 계약에 전달한다.
     *
     * @param principal 소비자 Access JWT로 구성한 인증 주체
     * @param status 선택 예약 상태
     * @param page 0부터 시작하는 페이지 번호
     * @param size 1~100 범위의 페이지 크기
     * @param sort 허용된 예약 내역 정렬
     * @return 공통 성공 봉투로 감싼 본인 예약 내역 페이지
     */
    @GetMapping("/me/reservations")
    public ApiResponse<ReservationHistoryPageResponse> getReservationHistory(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort
    ) {
        // C-013 계정 상태를 요청 값 오류보다 먼저 판정한다.
        consumerAccountService.getMe(principal.accountId());
        ReservationHistorySearchRequest request = ReservationHistorySearchRequest.from(
                status,
                page,
                size,
                sort
        );
        ReservationHistoryPageResponse response =
                reservationService.getConsumerReservationHistory(principal.accountId(), request);
        return ApiResponse.success("조회했습니다.", response);
    }

    /**
     * 닉네임을 수정한다. C-006에 따라 {@code Idempotency-Key}를 요구하고, 같은 키·같은 입력의
     * 재요청은 최초 결과를 그대로 재생한다(#32 공통 멱등 기반).
     *
     * <p>응답 {@code data}는 최초 실행이든 재생이든 저장된 같은 JSON을 그대로 내보내야 하므로
     * 도메인 DTO가 아니라 {@link JsonNode}로 받는다. 직렬화 결과는 {@code ConsumerAccount}
     * 스키마와 동일하다.</p>
     */
    @PatchMapping("/me")
    public ApiResponse<JsonNode> updateMe(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConsumerAccountUpdateRequest request
    ) {
        IdempotencyKey key = IdempotencyKey.parse(idempotencyKey);
        IdempotencyCommand command = new IdempotencyCommand(
                TokenNamespace.CONSUMER.value(),
                principal.accountId(),
                UPDATE_COMMAND_TYPE,
                key.value(),
                RequestFingerprint.of(canonicalUpdateInput(request)));

        IdempotentOutcome outcome = consumerAccountService.updateName(command, principal.accountId(), request);
        return ApiResponse.success("수정했습니다.", outcome.data());
    }

    /**
     * 지문 계산용 정규 입력이다. 대상 주체는 멱등 업무 키에 이미 포함되므로 경로와 승인된 body
     * field만 넣는다. 제출값을 그대로 쓰기 때문에 공백 등이 다른 요청은 다른 요청으로 취급된다.
     */
    private String canonicalUpdateInput(ConsumerAccountUpdateRequest request) {
        return UPDATE_ROUTE + "\nnickname=" + request.nickname();
    }
}
