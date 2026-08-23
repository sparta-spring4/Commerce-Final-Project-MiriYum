package com.miriyum.domain.menu.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.menu.image.MenuImageCommandResult;
import com.miriyum.domain.menu.image.MenuImageService;
import com.miriyum.domain.menu.image.MenuPublicImageResponse;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 매장 운영자가 자신이 소유한 메뉴의 대표 공개 이미지를 관리하는 HTTP 진입점이다. */
@RestController
@Validated
@RequestMapping("/api/v1/store-operators/stores/{storeId}/menus/{menuId}/images")
@RequiredArgsConstructor
public class MenuImageController {

    private final MenuImageService menuImageService;

    @GetMapping
    public ResponseEntity<ApiResponse<MenuPublicImageResponse>> get(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long menuId
    ) {
        MenuPublicImageResponse image = menuImageService.getMenuImage(
                principal.accountId(), storeId, menuId);
        if (image == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(ApiResponse.success("메뉴 대표 이미지를 조회했습니다.", image));
    }

    @PutMapping(consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<MenuPublicImageResponse>> put(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long menuId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestPart("file") MultipartFile file
    ) {
        MenuImageCommandResult result = menuImageService.putMenuImage(
                principal.accountId(), storeId, menuId, IdempotencyKey.parse(rawKey), file);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("메뉴 대표 이미지를 저장했습니다.", result.data()));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long menuId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey
    ) {
        menuImageService.deleteMenuImage(
                principal.accountId(), storeId, menuId, IdempotencyKey.parse(rawKey));
        return ResponseEntity.noContent().build();
    }
}
