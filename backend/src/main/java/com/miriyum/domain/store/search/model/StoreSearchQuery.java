package com.miriyum.domain.store.search.model;

import com.miriyum.domain.store.enums.Region;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;

/**
 * 공개 매장 검색 입력을 검증하고 SQL에 안전한 값으로 정규화한 조건이다.
 *
 * @param normalizedKeyword 공백 축약과 소문자 변환을 마친 검색어
 * @param likePattern literal wildcard escaping을 마친 LIKE 패턴
 * @param region 지역 필터
 * @param storeCategoryCode 주 카테고리 필터
 * @param reservationCondition 완전한 예약 검색 조건 또는 가용성 미요청이면 {@code null}
 * @param availableOnly 예약 가능한 매장만 요청하는지 여부
 * @param sort 허용 목록에서 선택한 고정 정렬
 * @param page 0부터 시작하는 페이지 번호
 * @param size 페이지 크기(1~100)
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
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 이미 정규화된 필드의 공개 불변식과 파생 LIKE 패턴의 일관성을 검증한다.
     *
     * @throws ServiceException 필드가 공개 검색 계약을 만족하지 않는 경우
     */
    public StoreSearchQuery {
        String canonicalKeyword = normalizeKeyword(normalizedKeyword);
        if (!Objects.equals(normalizedKeyword, canonicalKeyword)
                || !Objects.equals(likePattern, toLikePattern(canonicalKeyword))
                || (availableOnly && reservationCondition == null)
                || sort == null
                || page < 0
                || size < 1
                || size > MAX_PAGE_SIZE) {
            throw validationFailed();
        }
    }

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
        ReservationSearchCondition reservationCondition =
                ReservationSearchCondition.fromNullable(serviceDate, startTime, partySize);
        if ((availableOnly && reservationCondition == null)
                || page < 0
                || size < 1
                || size > MAX_PAGE_SIZE) {
            throw validationFailed();
        }

        String normalizedKeyword = normalizeKeyword(keyword);
        if (keyword != null && normalizedKeyword == null) {
            throw validationFailed();
        }
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
        String normalized = collapseWhitespace(keyword).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_KEYWORD_LENGTH) {
            throw validationFailed();
        }
        return normalized;
    }

    private static String collapseWhitespace(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isUnicodeWhitespace(codePoint)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return codePoint == 0x0085
                || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
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
