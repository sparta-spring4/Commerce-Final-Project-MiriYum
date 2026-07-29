package com.miriyum.store.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 하나의 catalog 항목이다.
 *
 * <p>불투명 코드와 한글 표시명, 활성 여부, 종류 안 정렬 순서를 가진다. seed 정본이 원천이며
 * 애플리케이션 런타임에서 생성·수정하지 않는다. 내부 {@code id}는 공개 응답에 노출하지 않는다.</p>
 */
@Entity
@Table(name = "catalog_item")
public class CatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CatalogKind kind;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected CatalogItem() {
    }

    public CatalogItem(CatalogKind kind, String code, String displayName, boolean active, int sortOrder) {
        this.kind = kind;
        this.code = code;
        this.displayName = displayName;
        this.active = active;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public CatalogKind getKind() {
        return kind;
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
