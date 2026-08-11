package com.miriyum.domain.menu.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.menu.dto.storeoperator.ManagedMenuResponse;
import com.miriyum.domain.menu.dto.storeoperator.MenuContentRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuChangeReasonRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuPublicationRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuSellingStatusRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuVisibilityRequest;
import com.miriyum.domain.menu.service.MenuCommandResult;
import com.miriyum.domain.menu.service.MenuCommandService;
import com.miriyum.domain.menu.service.MenuQueryService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/store-operators/stores/{storeId}/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuCommandService commandService;
    private final MenuQueryService queryService;

    @GetMapping
    public ApiResponse<List<ManagedMenuResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId
    ) {
        return ApiResponse.success("메뉴 목록을 조회했습니다.",
                queryService.list(principal.accountId(), storeId));
    }

    @GetMapping("/{menuId}")
    public ApiResponse<ManagedMenuResponse> get(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long menuId
    ) {
        return ApiResponse.success("메뉴를 조회했습니다.",
                queryService.get(principal.accountId(), storeId, menuId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ManagedMenuResponse>> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody MenuContentRequest request
    ) {
        return response("메뉴 초안을 저장했습니다.",
                commandService.create(principal.accountId(), storeId,
                        IdempotencyKey.parse(rawKey), request));
    }

    @PutMapping("/{menuId}")
    public ResponseEntity<ApiResponse<ManagedMenuResponse>> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long menuId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody MenuContentRequest request
    ) {
        return response("메뉴 내용을 수정해 새 초안을 저장했습니다.",
                commandService.update(principal.accountId(), storeId, menuId,
                        IdempotencyKey.parse(rawKey), request));
    }

    @PostMapping("/{menuId}/publication")
    public ResponseEntity<ApiResponse<ManagedMenuResponse>> publish(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long menuId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody MenuPublicationRequest request
    ) {
        return response("메뉴 게시 상태를 변경했습니다.",
                commandService.publish(principal.accountId(), storeId, menuId,
                        IdempotencyKey.parse(rawKey), request));
    }

    @PostMapping("/{menuId}/publication-cancellation")
    public ResponseEntity<ApiResponse<ManagedMenuResponse>> cancelPublication(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long menuId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody MenuChangeReasonRequest request
    ) {
        return response("예약 게시를 취소했습니다.",
                commandService.cancelPublication(principal.accountId(), storeId, menuId,
                        IdempotencyKey.parse(rawKey), request));
    }

    @PatchMapping("/{menuId}/visibility")
    public ResponseEntity<ApiResponse<ManagedMenuResponse>> changeVisibility(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long menuId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody MenuVisibilityRequest request
    ) {
        return response("메뉴 노출 상태를 변경했습니다.",
                commandService.changeVisibility(principal.accountId(), storeId, menuId,
                        IdempotencyKey.parse(rawKey), request));
    }

    @PatchMapping("/{menuId}/selling-status")
    public ResponseEntity<ApiResponse<ManagedMenuResponse>> changeSellingStatus(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long menuId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody MenuSellingStatusRequest request
    ) {
        return response("메뉴 판매 상태를 변경했습니다.",
                commandService.changeSellingStatus(principal.accountId(), storeId, menuId,
                        IdempotencyKey.parse(rawKey), request));
    }

    @PostMapping("/{menuId}/retirement")
    public ResponseEntity<ApiResponse<ManagedMenuResponse>> retire(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long menuId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody MenuChangeReasonRequest request
    ) {
        return response("메뉴를 운영 종료했습니다.",
                commandService.retire(principal.accountId(), storeId, menuId,
                        IdempotencyKey.parse(rawKey), request));
    }

    private ResponseEntity<ApiResponse<ManagedMenuResponse>> response(
            String message,
            MenuCommandResult result
    ) {
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(message, result.data()));
    }
}
