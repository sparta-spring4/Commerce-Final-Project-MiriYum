package com.miriyum.domain.store.service;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoreCatalogPolicy {

    private final CatalogService catalogService;

    public void validate(String categoryCode, List<String> tagCodes) {
        if (tagCodes.size() != new HashSet<>(tagCodes).size()) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (!catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, categoryCode)
                || !catalogService.findUnknownCodes(CatalogKind.STORE_TAG, tagCodes).isEmpty()) {
            throw new ServiceException(StoreErrorCode.CATALOG_CODE_INVALID);
        }
    }
}
