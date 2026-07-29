package com.miriyum.store.catalog.repository;

import com.miriyum.store.catalog.domain.CatalogItem;
import com.miriyum.store.catalog.domain.CatalogKind;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * catalog 항목 데이터 접근이다.
 *
 * <p>파생 쿼리만 사용하고 네이티브 SQL 문자열 연결을 사용하지 않는다.</p>
 */
public interface CatalogItemRepository extends JpaRepository<CatalogItem, Long> {

    /** 종류의 활성 항목을 정렬 순서 오름차순으로 조회한다. */
    List<CatalogItem> findByKindAndActiveTrueOrderBySortOrderAsc(CatalogKind kind);

    /** 종류 안에서 주어진 code 집합 중 활성 항목만 조회한다. */
    List<CatalogItem> findByKindAndActiveTrueAndCodeIn(CatalogKind kind, Collection<String> codes);

    /** 종류 안에 활성 code가 존재하는지 확인한다. */
    boolean existsByKindAndCodeAndActiveTrue(CatalogKind kind, String code);
}
