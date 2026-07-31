package com.miriyum.domain.store.core.service;

import com.miriyum.domain.store.core.dto.StoreCreateRequest;
import com.miriyum.domain.store.core.dto.StoreModesRequest;
import com.miriyum.domain.store.core.dto.StoreUpdateRequest;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.util.List;

public final class StoreCommandFingerprint {

    private StoreCommandFingerprint() {
    }

    public static String forCreate(StoreCreateRequest request) {
        StringBuilder canonical = new StringBuilder("POST|/api/v1/store-operator/stores|");
        append(canonical, "businessRegistrationNumber", request.businessRegistrationNumber());
        append(canonical, "businessType", request.businessType().name());
        append(canonical, "name", request.name());
        append(canonical, "description", request.description());
        append(canonical, "region", request.region().name());
        append(canonical, "address", request.address());
        append(canonical, "storeCategoryCode", request.storeCategoryCode());
        appendSorted(canonical, "tagCodes", request.tagCodes());
        appendModes(canonical, request.modes());
        append(
                canonical,
                "applicantSelfAttested",
                Boolean.toString(request.applicantSelfAttested()));
        append(
                canonical,
                "requiredTermsAgreed",
                Boolean.toString(request.requiredTermsAgreed()));
        return RequestFingerprint.of(canonical.toString());
    }

    public static String forUpdate(long storeId, StoreUpdateRequest request) {
        StringBuilder canonical =
                new StringBuilder("PATCH|/api/v1/store-operator/stores/{storeId}|");
        append(canonical, "storeId", Long.toString(storeId));
        append(canonical, "name", request.name());
        append(canonical, "description", request.description());
        append(canonical, "region", enumName(request.region()));
        append(canonical, "address", request.address());
        append(canonical, "storeCategoryCode", request.storeCategoryCode());
        appendSorted(canonical, "tagCodes", request.tagCodes());
        appendModes(canonical, request.modes());
        append(canonical, "operationStatus", enumName(request.operationStatus()));
        return RequestFingerprint.of(canonical.toString());
    }

    private static void appendModes(StringBuilder canonical, StoreModesRequest modes) {
        if (modes == null) {
            append(canonical, "modes", null);
            return;
        }
        append(canonical, "reservationEnabled", Boolean.toString(modes.reservationEnabled()));
        append(canonical, "menuHoldEnabled", Boolean.toString(modes.menuHoldEnabled()));
        append(canonical, "pickupEnabled", Boolean.toString(modes.pickupEnabled()));
    }

    private static void appendSorted(
            StringBuilder canonical,
            String fieldName,
            List<String> values
    ) {
        if (values == null) {
            append(canonical, fieldName, null);
            return;
        }
        List<String> sorted = values.stream().sorted().toList();
        append(canonical, fieldName + ".size", Integer.toString(sorted.size()));
        for (int index = 0; index < sorted.size(); index++) {
            append(canonical, fieldName + "[" + index + "]", sorted.get(index));
        }
    }

    private static void append(StringBuilder canonical, String fieldName, String value) {
        canonical.append(fieldName).append('=');
        if (value == null) {
            canonical.append("-1:");
        } else {
            canonical.append(value.length()).append(':').append(value);
        }
        canonical.append('|');
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
