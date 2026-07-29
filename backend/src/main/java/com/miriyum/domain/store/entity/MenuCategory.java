package com.miriyum.domain.store.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 메뉴 주·보조 카테고리 catalog 항목이다.
 */
@Entity
@Table(name = "menu_category")
public class MenuCategory extends CatalogEntry {

    protected MenuCategory() {
    }

    public MenuCategory(String code, String displayName, boolean active, int sortOrder) {
        super(code, displayName, active, sortOrder);
    }
}
