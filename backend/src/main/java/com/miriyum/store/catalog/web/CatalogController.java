package com.miriyum.store.catalog.web;

import com.miriyum.global.response.ApiResponse;
import com.miriyum.store.catalog.domain.CatalogKind;
import com.miriyum.store.catalog.service.CatalogService;
import com.miriyum.store.catalog.web.dto.CatalogItemResponse;
import com.miriyum.store.catalog.web.dto.CatalogListResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비회원도 접근하는 공개 catalog 조회 API이다.
 *
 * <p>활성 항목만 공통 성공 봉투로 반환한다. 인증·인가 필터 구성은 이 컨트롤러가 소유하지 않는다.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/store-categories")
    public ApiResponse<CatalogListResponse> getStoreCategories() {
        return ApiResponse.success("매장 카테고리를 조회했습니다.", list(CatalogKind.STORE_CATEGORY));
    }

    @GetMapping("/menu-categories")
    public ApiResponse<CatalogListResponse> getMenuCategories() {
        return ApiResponse.success("메뉴 카테고리를 조회했습니다.", list(CatalogKind.MENU_CATEGORY));
    }

    @GetMapping("/store-tags")
    public ApiResponse<CatalogListResponse> getStoreTags() {
        return ApiResponse.success("매장 태그를 조회했습니다.", list(CatalogKind.STORE_TAG));
    }

    private CatalogListResponse list(CatalogKind kind) {
        List<CatalogItemResponse> items = catalogService.getItems(kind).stream()
                .map(CatalogItemResponse::from)
                .toList();
        return new CatalogListResponse(items);
    }
}
