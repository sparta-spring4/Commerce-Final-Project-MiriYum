package com.miriyum.domain.store.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.store.dto.image.PublicImageResponse;
import com.miriyum.domain.store.image.PublicImageCommandResult;
import com.miriyum.domain.store.image.PublicImageService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 매장 운영자가 매장 공개 이미지를 관리하는 HTTP 진입점이다. */
@RestController
@RequestMapping("/api/v1/store-operators/stores/{storeId}/images")
@RequiredArgsConstructor
public class StoreImageController {

    private final PublicImageService publicImageService;

    @GetMapping
    public ApiResponse<List<PublicImageResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId
    ) {
        return ApiResponse.success("매장 이미지 목록을 조회했습니다.",
                publicImageService.listStoreImages(principal.accountId(), storeId));
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<PublicImageResponse>> upload(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestPart("file") MultipartFile file
    ) {
        return response("매장 이미지를 등록했습니다.", publicImageService.uploadStoreImage(
                principal.accountId(), storeId, IdempotencyKey.parse(rawKey), file));
    }

    @PutMapping(value = "/{imageId}", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<PublicImageResponse>> replace(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable UUID imageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestPart("file") MultipartFile file
    ) {
        return response("매장 이미지를 교체했습니다.", publicImageService.replaceStoreImage(
                principal.accountId(), storeId, imageId, IdempotencyKey.parse(rawKey), file));
    }

    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable UUID imageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey
    ) {
        publicImageService.deleteStoreImage(principal.accountId(), storeId, imageId,
                IdempotencyKey.parse(rawKey));
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<ApiResponse<PublicImageResponse>> response(
            String message, PublicImageCommandResult result
    ) {
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(message, result.data()));
    }
}
