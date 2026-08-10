package com.miriyum.domain.store.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreCatalogPolicyTest {

    @Mock
    private CatalogService catalogService;

    @InjectMocks
    private StoreCatalogPolicy policy;

    @Test
    @DisplayName("비활성 매장 카테고리는 STORE_004로 거부한다")
    void rejectsInactiveCategory() {
        given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "UNKNOWN"))
                .willReturn(false);

        assertThatThrownBy(() -> policy.validate("UNKNOWN", List.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.CATALOG_CODE_INVALID);
    }

    @Test
    @DisplayName("중복 태그는 catalog 조회 전에 COMMON_001로 거부한다")
    void rejectsDuplicateTagsBeforeCatalogLookup() {
        assertThatThrownBy(() -> policy.validate("KOREAN", List.of("DATE", "DATE")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("미승인 태그가 하나라도 있으면 STORE_004로 거부한다")
    void rejectsUnknownTag() {
        given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "KOREAN"))
                .willReturn(true);
        given(catalogService.findUnknownCodes(
                CatalogKind.STORE_TAG, List.of("DATE", "UNKNOWN")))
                .willReturn(List.of("UNKNOWN"));

        assertThatThrownBy(() ->
                policy.validate("KOREAN", List.of("DATE", "UNKNOWN")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.CATALOG_CODE_INVALID);
    }

    @Test
    @DisplayName("활성 카테고리와 중복 없는 승인 태그는 통과한다")
    void acceptsActiveCatalogCodes() {
        given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "KOREAN"))
                .willReturn(true);
        given(catalogService.findUnknownCodes(
                CatalogKind.STORE_TAG, List.of("DATE", "QUIET")))
                .willReturn(List.of());

        assertThatCode(() -> policy.validate(
                "KOREAN", List.of("DATE", "QUIET"))).doesNotThrowAnyException();
    }
}
