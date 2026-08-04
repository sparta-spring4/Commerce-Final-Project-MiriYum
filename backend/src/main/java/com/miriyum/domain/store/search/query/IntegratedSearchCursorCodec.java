package com.miriyum.domain.store.search.query;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 검색 fingerprint와 정렬을 결합한 versioned opaque cursor codec이다. */
public final class IntegratedSearchCursorCodec {

    private static final String VERSION = "v1";
    private static final int MAX_CURSOR_LENGTH = 1_024;

    private IntegratedSearchCursorCodec() {
    }

    public static String encode(
            IntegratedStoreSearchQuery query,
            String sortValue,
            long storeId
    ) {
        if (query == null || sortValue == null || storeId <= 0) {
            throw new IllegalArgumentException("query and cursor values are required");
        }
        String encodedSortValue = Base64.getUrlEncoder().withoutPadding().encodeToString(
                sortValue.getBytes(StandardCharsets.UTF_8));
        return String.join(
                ".",
                VERSION,
                query.fingerprint(),
                query.sort().name(),
                encodedSortValue,
                Long.toString(storeId));
    }

    static IntegratedSearchCursor decode(
            String rawCursor,
            String expectedFingerprint,
            IntegratedStoreSearchSort expectedSort
    ) {
        try {
            if (rawCursor == null
                    || rawCursor.isBlank()
                    || rawCursor.length() > MAX_CURSOR_LENGTH) {
                throw validationFailed();
            }
            String[] parts = rawCursor.split("\\.", -1);
            if (parts.length != 5
                    || !VERSION.equals(parts[0])
                    || !expectedFingerprint.equals(parts[1])
                    || !expectedSort.name().equals(parts[2])) {
                throw validationFailed();
            }
            String sortValue = new String(
                    Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
            long storeId = Long.parseLong(parts[4]);
            if (sortValue.isEmpty() || storeId <= 0) {
                throw validationFailed();
            }
            return new IntegratedSearchCursor(sortValue, storeId);
        } catch (IllegalArgumentException exception) {
            throw validationFailed();
        }
    }

    private static ServiceException validationFailed() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
