package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketView;

public record MenuInventoryCommandResult(
        int httpStatus,
        InventoryBucketView data
) {
}
