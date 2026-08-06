package com.miriyum.domain.store.search.query;

import com.miriyum.domain.store.search.interpreter.PriceRange;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;

final class SearchQueryFingerprint {

    private SearchQueryFingerprint() {
    }

    static String create(
            List<String> regionCodes,
            List<String> storeCategoryCodes,
            List<String> menuCategoryCodes,
            List<String> tagCodes,
            PriceRange priceRange,
            Integer partySize,
            LocalDate reservationDate,
            LocalTime reservationTime,
            String remainingKeyword,
            IntegratedStoreSearchSort sort,
            int size
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, regionCodes);
            update(digest, storeCategoryCodes);
            update(digest, menuCategoryCodes);
            update(digest, tagCodes);
            update(digest, priceRange == null ? null : priceRange.minInclusive());
            update(digest, priceRange == null ? null : priceRange.maxInclusive());
            update(digest, partySize);
            update(digest, reservationDate);
            update(digest, reservationTime);
            update(digest, remainingKeyword);
            update(digest, sort.name());
            update(digest, size);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void update(MessageDigest digest, List<String> values) {
        updateLength(digest, values.size());
        values.forEach(value -> update(digest, value));
    }

    private static void update(MessageDigest digest, Object value) {
        if (value == null) {
            updateLength(digest, -1);
            return;
        }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        updateLength(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateLength(MessageDigest digest, int length) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
    }
}
