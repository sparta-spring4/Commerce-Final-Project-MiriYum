package com.miriyum.domain.store.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 매장 주 카테고리 catalog 항목이다.
 */
@Entity
@Table(name = "store_category")
public class StoreCategory extends CatalogEntry {

    protected StoreCategory() {
    }

    public StoreCategory(String code, String displayName, boolean active, int sortOrder) {
        super(code, displayName, active, sortOrder);
    }
}
