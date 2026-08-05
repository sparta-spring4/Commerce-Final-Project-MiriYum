package com.miriyum.domain.store.search.dto;

import java.util.List;

public record PublicMenuList(List<PublicMenu> items) {
    public PublicMenuList {
        items = List.copyOf(items);
    }
}
