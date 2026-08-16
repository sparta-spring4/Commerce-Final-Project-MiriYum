package com.miriyum.domain.reservation.waiting.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.waiting.dto.WaitingSettingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingSettingDeactivationImpact;
import com.miriyum.domain.reservation.waiting.dto.WaitingSettingSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingSettingUpdateRequest;
import com.miriyum.domain.reservation.waiting.service.WaitingSettingService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증된 매장 운영자에게 웨이팅 설정 조회·영향 조회·전체 교체 API를 제공한다.
 */
@RestController
@RequestMapping("/api/v1/store-operators/stores/{storeId}/waiting-settings")
@RequiredArgsConstructor
public class WaitingSettingStoreOperatorController {
    private final WaitingSettingService service;

    /**
     * 설정 부재 시에도 안전 기본값을 포함한 200 응답을 반환한다.
     *
     * @param principal 인증된 매장 운영자
     * @param storeId 조회할 매장 ID
     * @return 현재 웨이팅 설정
     */
    @GetMapping
    public ApiResponse<WaitingSettingSnapshot> get(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId) {
        return ApiResponse.success("웨이팅 설정을 조회했습니다.",
                service.get(principal.accountId(), storeId));
    }

    /**
     * 기능 종료 전에 현재 설정 version과 활성 팀 수를 조회한다.
     *
     * @param principal 인증된 매장 운영자
     * @param storeId 조회할 매장 ID
     * @return 비활성화 영향
     */
    @GetMapping("/deactivation-impact")
    public ApiResponse<WaitingSettingDeactivationImpact> inspectDeactivation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId) {
        return ApiResponse.success("웨이팅 비활성화 영향을 조회했습니다.",
                service.inspectDeactivation(principal.accountId(), storeId));
    }

    /**
     * {@code expectedVersion}과 {@code Idempotency-Key}로 설정 전체를 교체한다.
     *
     * @param principal 인증된 매장 운영자
     * @param storeId 변경할 매장 ID
     * @param rawKey 멱등 키 헤더
     * @param request 전체 교체 요청
     * @return 일반 교체는 200, 비동기 활성 팀 종결 작업 생성은 202
     */
    @PutMapping
    public ResponseEntity<ApiResponse<Object>> replace(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingSettingUpdateRequest request) {
        WaitingSettingCommandResult result = service.replace(
                principal.accountId(), storeId, IdempotencyKey.parse(rawKey), request);
        String message = result.httpStatus() == 202
                ? "웨이팅 설정을 변경하고 종료 작업을 생성했습니다."
                : "웨이팅 설정을 변경했습니다.";
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(message, result.data()));
    }
}
