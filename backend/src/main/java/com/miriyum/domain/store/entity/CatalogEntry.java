package com.miriyum.domain.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * 종류별 catalog 테이블이 공유하는 항목 매핑이다.
 *
 * <p>불투명 code를 자연 기본키로 사용해 후속 도메인이 code 단독 FK를 걸 수 있게 한다. code는
 * 대소문자를 구분하는 collation을 사용하며 seed 정본이 원천이다(런타임 생성·수정 없음).</p>
 */
@MappedSuperclass
public abstract class CatalogEntry {

    @Id
    @Column(length = 50)
    private String code;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected CatalogEntry() {
    }

    protected CatalogEntry(String code, String displayName, boolean active, int sortOrder) {
        this.code = code;
        this.displayName = displayName;
        this.active = active;
        this.sortOrder = sortOrder;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
