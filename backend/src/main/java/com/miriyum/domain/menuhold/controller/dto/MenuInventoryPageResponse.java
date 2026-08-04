package com.miriyum.domain.menuhold.controller.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record MenuInventoryPageResponse(
        List<MenuInventoryBucketResponse> items,
        PageMetadata page
) {
    public static MenuInventoryPageResponse from(
            Page<com.miriyum.domain.menuhold.inventory.dto.InventoryBucketView> source
    ) {
        return new MenuInventoryPageResponse(
                source.getContent().stream()
                        .map(MenuInventoryBucketResponse::from).toList(),
                new PageMetadata(source.getNumber(), source.getSize(),
                        source.getTotalElements(), source.getTotalPages(),
                        source.hasNext()));
    }

    public record PageMetadata(
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext
    ) {
    }
}
