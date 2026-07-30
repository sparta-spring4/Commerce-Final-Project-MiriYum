package com.miriyum.domain.store.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 매장 검색 보조 태그 catalog 항목이다.
 */
@Entity
@Table(name = "store_tag")
public class StoreTag extends CatalogEntry {

    protected StoreTag() {
    }

    private StoreTag(
            String code,
            String displayName,
            boolean active,
            int sortOrder
    ) {
        super(code, displayName, active, sortOrder);
    }

    public static StoreTag of(
            String code,
            String displayName,
            boolean active,
            int sortOrder
    ) {
        return new StoreTag(code, displayName, active, sortOrder);
    }
}
