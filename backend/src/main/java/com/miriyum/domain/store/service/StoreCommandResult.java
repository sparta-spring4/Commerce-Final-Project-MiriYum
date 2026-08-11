package com.miriyum.domain.store.service;

import com.miriyum.domain.store.dto.storeoperator.ManagedStoreResponse;

public record StoreCommandResult(
        int httpStatus,
        ManagedStoreResponse data
) {
}
