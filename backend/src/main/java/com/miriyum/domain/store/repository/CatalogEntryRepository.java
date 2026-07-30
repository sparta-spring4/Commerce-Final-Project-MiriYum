package com.miriyum.domain.store.repository;

import com.miriyum.domain.store.entity.CatalogEntry;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 종류별 catalog 테이블이 공유하는 조회 계약이다.
 *
 * <p>파생 쿼리만 사용하고, 정렬은 `sort_order` 다음 `code`로 결정적이다. 구체 repository가 이 계약을 상속한다.</p>
 *
 * @param <T> 종류별 catalog 엔티티
 */
@NoRepositoryBean
public interface CatalogEntryRepository<T extends CatalogEntry> extends JpaRepository<T, String> {

    /** 활성 항목을 정렬 순서·code 오름차순으로 조회한다. */
    List<T> findByActiveTrueOrderBySortOrderAscCodeAsc();

    /** 활성 code가 존재하는지 확인한다(대소문자 구분 collation). */
    boolean existsByCodeAndActiveTrue(String code);

    /** 주어진 code 집합 중 활성 항목만 조회한다. */
    List<T> findByActiveTrueAndCodeIn(Collection<String> codes);
}
