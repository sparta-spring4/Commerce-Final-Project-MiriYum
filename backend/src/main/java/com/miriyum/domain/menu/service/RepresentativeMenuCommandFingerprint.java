package com.miriyum.domain.menu.service;

import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuReplaceRequest;
import com.miriyum.global.idempotency.RequestFingerprint;

public final class RepresentativeMenuCommandFingerprint {

    private RepresentativeMenuCommandFingerprint() {
    }

    public static String of(long storeId, RepresentativeMenuReplaceRequest request) {
        return RequestFingerprint.of(
                "REPLACE|storeId=" + storeId
                        + "|expectedVersion=" + request.expectedVersion()
                        + "|menuIds=" + request.menuIds());
    }
}
