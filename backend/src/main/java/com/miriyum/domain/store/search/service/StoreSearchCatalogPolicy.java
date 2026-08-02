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

    public void requireActiveStoreCategory(String code) {
        if (code == null) {
            return;
        }
        if (!catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, code)) {
            throw new ServiceException(StoreErrorCode.CATALOG_CODE_INVALID);
        }
    }
}
