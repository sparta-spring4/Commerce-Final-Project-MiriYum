package com.miriyum.domain.menuhold.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.menuhold.controller.dto.MenuInventoryBucketResponse;
import com.miriyum.domain.menuhold.controller.dto.MenuInventoryCreateRequest;
import com.miriyum.domain.menuhold.controller.dto.MenuInventoryPageResponse;
import com.miriyum.domain.menuhold.controller.dto.MenuInventoryUpdateRequest;
import com.miriyum.domain.menuhold.service.MenuInventoryAdminCommandService;
import com.miriyum.domain.menuhold.service.MenuInventoryAdminService;
import com.miriyum.domain.menuhold.service.MenuInventoryCommandResult;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/store-operator/stores/{storeId}/menu-inventory-buckets")
@RequiredArgsConstructor
public class MenuInventoryAdminController {

    private final MenuInventoryAdminService queryService;
    private final MenuInventoryAdminCommandService commandService;

    @GetMapping
    public ApiResponse<MenuInventoryPageResponse> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestParam(required = false) LocalDate serviceDate,
            @RequestParam(required = false) @Positive Long menuId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        var result = queryService.list(principal.accountId(), storeId,
                serviceDate, menuId, PageRequest.of(page, size));
        return ApiResponse.success("메뉴 재고 버킷을 조회했습니다.",
                MenuInventoryPageResponse.from(result));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MenuInventoryBucketResponse>> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody MenuInventoryCreateRequest request
    ) {
        MenuInventoryCommandResult result = commandService.create(
                principal.accountId(), storeId, IdempotencyKey.parse(rawKey),
                request.toCommand());
        return ResponseEntity.status(result.httpStatus()).body(ApiResponse.success(
                "메뉴 재고 버킷을 생성했습니다.",
                MenuInventoryBucketResponse.from(result.data())));
    }

    @PatchMapping("/{inventoryBucketId}")
    public ResponseEntity<ApiResponse<MenuInventoryBucketResponse>> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long inventoryBucketId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody MenuInventoryUpdateRequest request
    ) {
        MenuInventoryCommandResult result = commandService.update(
                principal.accountId(), storeId, inventoryBucketId,
                IdempotencyKey.parse(rawKey), request.toCommand());
        return ResponseEntity.status(result.httpStatus()).body(ApiResponse.success(
                "메뉴 재고 버킷 정책을 수정했습니다.",
                MenuInventoryBucketResponse.from(result.data())));
    }
}
