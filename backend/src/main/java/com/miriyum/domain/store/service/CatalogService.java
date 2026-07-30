package com.miriyum.domain.store.service;

import com.miriyum.domain.store.entity.CatalogEntry;
import com.miriyum.domain.store.repository.CatalogEntryRepository;
import com.miriyum.domain.store.repository.MenuCategoryRepository;
import com.miriyum.domain.store.repository.StoreCategoryRepository;
import com.miriyum.domain.store.repository.StoreTagRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * catalog 조회와 코드 검증을 제공한다.
 *
 * <p>검증 메서드는 후속 매장·메뉴 도메인이 재사용하는 공개 계약이다. {@code STORE_004}를 던지지 않고
 * 중립 결과만 반환한다. 오류 매핑과 "카테고리 1개·보조 0~5개 중복 불가" 같은 개수·중복 규칙은
 * 소비 도메인이 소유한다. code 비교는 DB의 대소문자 구분 collation과 Java 비교가 일치한다.</p>
 */
@Service
public class CatalogService {

    private final StoreCategoryRepository storeCategoryRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final StoreTagRepository storeTagRepository;

    public CatalogService(
            StoreCategoryRepository storeCategoryRepository,
            MenuCategoryRepository menuCategoryRepository,
            StoreTagRepository storeTagRepository) {
        this.storeCategoryRepository = storeCategoryRepository;
        this.menuCategoryRepository = menuCategoryRepository;
        this.storeTagRepository = storeTagRepository;
    }

    /** 종류의 활성 항목을 정렬 순서대로 반환한다. */
    @Transactional(readOnly = true)
    public List<CatalogItemView> getItems(CatalogKind kind) {
        return repositoryFor(kind).findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .map(entry -> new CatalogItemView(entry.getCode(), entry.getDisplayName()))
                .toList();
    }

    /** 종류 안에 활성 code가 존재하면 {@code true}. */
    @Transactional(readOnly = true)
    public boolean isActiveCode(CatalogKind kind, String code) {
        if (code == null) {
            return false;
        }
        return repositoryFor(kind).existsByCodeAndActiveTrue(code);
    }

    /**
     * 입력 코드 중 종류 안의 활성 catalog에 없는 코드를 반환한다.
     *
     * <p>계약: {@code codes}가 {@code null}이거나 비어 있으면 빈 목록(전부 유효)을 반환한다. {@code codes}에
     * {@code null} 원소가 있으면 잘못된 입력으로 보고 {@link IllegalArgumentException}으로 거부한다. 반환
     * 목록에는 절대 {@code null}이 포함되지 않으므로 소비 도메인의 오류 메시지 생성·직렬화에서 안전하다.
     * ({@link #isActiveCode} 단건 조회는 {@code null}에 {@code false}를 반환하는 관대한 질의이지만, 다건
     * 검증은 오류 매핑 입력이므로 {@code null} 원소를 조용히 통과시키지 않는다.)</p>
     *
     * @return 미승인·비활성 코드 목록(모두 non-null). 비어 있으면 전부 유효하다.
     * @throws IllegalArgumentException {@code codes}에 {@code null} 원소가 있는 경우
     */
    @Transactional(readOnly = true)
    public List<String> findUnknownCodes(CatalogKind kind, Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        if (codes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("codes must not contain null");
        }
        Set<String> knownCodes = repositoryFor(kind).findByActiveTrueAndCodeIn(codes).stream()
                .map(CatalogEntry::getCode)
                .collect(Collectors.toSet());
        return codes.stream()
                .distinct()
                .filter(code -> !knownCodes.contains(code))
                .toList();
    }

    private CatalogEntryRepository<? extends CatalogEntry> repositoryFor(CatalogKind kind) {
        return switch (kind) {
            case STORE_CATEGORY -> storeCategoryRepository;
            case MENU_CATEGORY -> menuCategoryRepository;
            case STORE_TAG -> storeTagRepository;
        };
    }
}
