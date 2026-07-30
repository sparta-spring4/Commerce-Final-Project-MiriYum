package com.miriyum.domain.store.core.service;

import com.miriyum.domain.store.core.dto.ManagedStoreResponse;

public record StoreCommandResult(
        int httpStatus,
        ManagedStoreResponse data
) {
}
