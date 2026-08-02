package com.miriyum.domain.store.search.service;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import com.miriyum.global.exception.ServiceException;
import org.springframework.stereotype.Component;

/**
 * Catalog의 중립 검증 결과를 공개 매장 검색 오류 계약으로 변환한다.
 */
@Component
public class StoreSearchCatalogPolicy {

    private final CatalogService catalogService;

    public StoreSearchCatalogPolicy(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * 선택된 매장 주 카테고리 코드가 현재 활성 카탈로그 코드인지 확인한다.
     *
     * <p>{@code null}은 카테고리 필터가 없는 검색으로 간주해 검증을 생략한다.</p>
     *
     * @param code 검증할 불투명 매장 카테고리 코드 또는 필터가 없으면 {@code null}
     * @throws ServiceException 코드가 미승인 또는 비활성이면
     *                          {@link StoreErrorCode#CATALOG_CODE_INVALID}
     */
    public void requireActiveStoreCategory(String code) {
        if (code == null) {
            return;
        }
        if (!catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, code)) {
            throw new ServiceException(StoreErrorCode.CATALOG_CODE_INVALID);
        }
    }
}
