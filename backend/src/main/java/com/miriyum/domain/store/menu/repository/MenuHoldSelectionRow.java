package com.miriyum.domain.store.menu.repository;

/** Store 존재 여부와 메뉴 홀드 선택 후보를 한 조회에서 구분하는 내부 projection이다. */
public interface MenuHoldSelectionRow {

    Long getStoreId();

    Long getMenuId();

    String getMenuName();

    Integer getUnitPrice();
}
