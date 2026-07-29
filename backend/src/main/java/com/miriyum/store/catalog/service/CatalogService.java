package com.miriyum.store.catalog.service;

import com.miriyum.store.catalog.domain.CatalogItem;
import com.miriyum.store.catalog.domain.CatalogKind;
import com.miriyum.store.catalog.repository.CatalogItemRepository;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * catalog 조회와 코드 검증을 제공한다.
 *
 * <p>검증 메서드는 후속 매장·메뉴 도메인이 재사용하는 공개 계약이다. 이 메서드들은 {@code STORE_004}를
 * 던지지 않고 중립 결과만 반환한다. 오류 매핑과 "카테고리 1개·보조 0~5개 중복 불가" 같은 개수·중복
 * 규칙은 소비 도메인이 소유한다.</p>
 */
@Service
public class CatalogService {

    private final CatalogItemRepository catalogItemRepository;

    public CatalogService(CatalogItemRepository catalogItemRepository) {
        this.catalogItemRepository = catalogItemRepository;
    }

    /** 종류의 활성 항목을 정렬 순서대로 반환한다. */
    @Transactional(readOnly = true)
    public List<CatalogItemView> getItems(CatalogKind kind) {
        return catalogItemRepository.findByKindAndActiveTrueOrderBySortOrderAsc(kind).stream()
                .map(item -> new CatalogItemView(item.getCode(), item.getDisplayName()))
                .toList();
    }

    /** 종류 안에 활성 code가 존재하면 {@code true}. */
    @Transactional(readOnly = true)
    public boolean isActiveCode(CatalogKind kind, String code) {
        if (code == null) {
            return false;
        }
        return catalogItemRepository.existsByKindAndCodeAndActiveTrue(kind, code);
    }

    /**
     * 입력 코드 중 종류 안의 활성 catalog에 없는 코드를 반환한다.
     *
     * @return 미승인·비활성 코드 목록. 비어 있으면 전부 유효하다.
     */
    @Transactional(readOnly = true)
    public List<String> findUnknownCodes(CatalogKind kind, Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Set<String> knownCodes = catalogItemRepository.findByKindAndActiveTrueAndCodeIn(kind, codes).stream()
                .map(CatalogItem::getCode)
                .collect(Collectors.toSet());
        return codes.stream()
                .distinct()
                .filter(code -> !knownCodes.contains(code))
                .toList();
    }
}
