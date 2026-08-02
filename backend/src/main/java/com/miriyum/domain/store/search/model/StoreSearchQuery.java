package com.miriyum.domain.store.search.model;

import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;

/**
 * 공개 매장 검색 입력을 검증하고 SQL에 안전한 값으로 정규화한 조건이다.
 */
public record StoreSearchQuery(
        String normalizedKeyword,
        String likePattern,
        Region region,
        String storeCategoryCode,
        ReservationSearchCondition reservationCondition,
        boolean availableOnly,
        StoreSearchSort sort,
        int page,
        int size
) {

    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final int MAX_PARTY_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 100;

    public static StoreSearchQuery from(
            String keyword,
            Region region,
            String storeCategoryCode,
            LocalDate serviceDate,
            LocalTime startTime,
            Integer partySize,
            boolean availableOnly,
            String sort,
            int page,
            int size
    ) {
        boolean anyReservationValue = serviceDate != null
                || startTime != null
                || partySize != null;
        boolean allReservationValues = serviceDate != null
                && startTime != null
                && partySize != null;
        if (anyReservationValue != allReservationValues
                || (availableOnly && !allReservationValues)
                || (partySize != null
                && (partySize < 1 || partySize > MAX_PARTY_SIZE))
                || page < 0
                || size < 1
                || size > MAX_PAGE_SIZE) {
            throw validationFailed();
        }

        String normalizedKeyword = normalizeKeyword(keyword);
        ReservationSearchCondition reservationCondition = allReservationValues
                ? new ReservationSearchCondition(serviceDate, startTime, partySize)
                : null;
        return new StoreSearchQuery(
                normalizedKeyword,
                toLikePattern(normalizedKeyword),
                region,
                storeCategoryCode,
                reservationCondition,
                availableOnly,
                StoreSearchSort.parse(sort),
                page,
                size);
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.strip()
                .replaceAll("\\p{javaWhitespace}+", " ")
                .toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > MAX_KEYWORD_LENGTH) {
            throw validationFailed();
        }
        return normalized;
    }

    private static String toLikePattern(String keyword) {
        if (keyword == null) {
            return null;
        }
        String escaped = keyword
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static ServiceException validationFailed() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
