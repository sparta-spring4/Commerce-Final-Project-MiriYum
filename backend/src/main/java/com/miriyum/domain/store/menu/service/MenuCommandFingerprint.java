package com.miriyum.domain.store.menu.service;

import com.miriyum.global.idempotency.RequestFingerprint;

public final class MenuCommandFingerprint {

    private MenuCommandFingerprint() {
    }

    public static String of(String command, long storeId, long menuId, Object request) {
        return RequestFingerprint.of(
                command + "|storeId=" + storeId + "|menuId=" + menuId + "|body=" + request);
    }
}
