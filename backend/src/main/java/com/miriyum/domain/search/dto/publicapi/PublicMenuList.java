package com.miriyum.domain.search.dto.publicapi;

import java.util.List;

public record PublicMenuList(List<PublicMenu> items) {
    public PublicMenuList {
        items = List.copyOf(items);
    }
}
