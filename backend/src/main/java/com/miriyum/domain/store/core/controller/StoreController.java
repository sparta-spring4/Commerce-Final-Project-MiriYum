package com.miriyum.domain.store.core.controller;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.store.core.dto.ManagedStoreResponse;
import com.miriyum.domain.store.core.dto.StoreCreateRequest;
import com.miriyum.domain.store.core.dto.StoreUpdateRequest;
import com.miriyum.domain.store.core.service.StoreCommandResult;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/store-operator/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @PostMapping
    public ResponseEntity<ApiResponse<ManagedStoreResponse>> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody StoreCreateRequest request
    ) {
        StoreCommandResult result = storeService.create(
                principal.accountId(),
                IdempotencyKey.parse(rawKey),
                request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("매장이 등록되었습니다.", result.data()));
    }

    @GetMapping("/{storeId}")
    public ApiResponse<ManagedStoreResponse> get(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId
    ) {
        return ApiResponse.success(
                "조회되었습니다.",
                storeService.getManagedStore(principal.accountId(), storeId));
    }

    @PatchMapping("/{storeId}")
    public ResponseEntity<ApiResponse<ManagedStoreResponse>> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody StoreUpdateRequest request
    ) {
        StoreCommandResult result = storeService.update(
                principal.accountId(),
                storeId,
                IdempotencyKey.parse(rawKey),
                request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("수정되었습니다.", result.data()));
    }
}
