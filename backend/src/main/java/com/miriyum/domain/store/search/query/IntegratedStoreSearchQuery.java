package com.miriyum.domain.store.search.query;

import com.miriyum.domain.store.search.interpreter.InterpretedSearchCondition;
import com.miriyum.domain.store.search.interpreter.PriceRange;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** #110의 허용 조건을 QueryDSL 조회와 seek cursor에 사용할 불변 입력으로 만든다. */
public final class IntegratedStoreSearchQuery {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    private final List<String> regionCodes;
    private final List<String> storeCategoryCodes;
    private final List<String> menuCategoryCodes;
    private final List<String> tagCodes;
    private final PriceRange priceRange;
    private final Integer partySize;
    private final LocalDate reservationDate;
    private final LocalTime reservationTime;
    private final String remainingKeyword;
    private final IntegratedStoreSearchSort sort;
    private final int size;
    private final String fingerprint;
    private final IntegratedSearchCursor cursor;

    private IntegratedStoreSearchQuery(
            InterpretedSearchCondition condition,
            IntegratedStoreSearchSort sort,
            String rawCursor,
            int size,
            IntegratedSearchCursorCodec cursorCodec
    ) {
        this.regionCodes = canonicalCodes(condition.regionCodes());
        this.storeCategoryCodes = canonicalCodes(condition.storeCategoryCodes());
        this.menuCategoryCodes = canonicalCodes(condition.menuCategoryCodes());
        this.tagCodes = canonicalCodes(condition.tagCodes());
        this.priceRange = condition.priceRange();
        this.partySize = condition.partySize();
        this.reservationDate = condition.reservationDate();
        this.reservationTime = condition.reservationTime();
        this.remainingKeyword = condition.remainingKeyword();
        this.sort = sort;
        this.size = size;
        this.fingerprint = SearchQueryFingerprint.create(
                regionCodes,
                storeCategoryCodes,
                menuCategoryCodes,
                tagCodes,
                priceRange,
                partySize,
                reservationDate,
                reservationTime,
                remainingKeyword,
                sort,
                size);
        IntegratedSearchCursor decodedCursor = rawCursor == null
                ? null
                : cursorCodec.decode(rawCursor, fingerprint, sort);
        validateCursorSortValue(sort, decodedCursor);
        this.cursor = decodedCursor;
    }

    public static IntegratedStoreSearchQuery from(
            InterpretedSearchCondition condition,
            String sort,
            String cursor,
            Integer size,
            IntegratedSearchCursorCodec cursorCodec
    ) {
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(cursorCodec, "cursorCodec must not be null");
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw validationFailed();
        }
        return new IntegratedStoreSearchQuery(
                condition,
                IntegratedStoreSearchSort.parse(sort),
                cursor,
                resolvedSize,
                cursorCodec);
    }

    private static List<String> canonicalCodes(List<String> values) {
        if (values == null || values.stream().anyMatch(
                value -> value == null || value.isBlank() || !value.equals(value.trim()))) {
            throw validationFailed();
        }
        return values.stream().distinct().sorted().toList();
    }

    private static ServiceException validationFailed() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }

    private static void validateCursorSortValue(
            IntegratedStoreSearchSort sort,
            IntegratedSearchCursor cursor
    ) {
        if (cursor == null
                || sort == IntegratedStoreSearchSort.RELEVANCE_DESC
                || sort == IntegratedStoreSearchSort.NAME_ASC
                || sort == IntegratedStoreSearchSort.NAME_DESC) {
            return;
        }
        try {
            LocalDateTime.parse(cursor.sortValue());
        } catch (DateTimeParseException exception) {
            throw validationFailed();
        }
    }

    public List<String> regionCodes() {
        return regionCodes;
    }

    public List<String> storeCategoryCodes() {
        return storeCategoryCodes;
    }

    public List<String> menuCategoryCodes() {
        return menuCategoryCodes;
    }

    public List<String> tagCodes() {
        return tagCodes;
    }

    public PriceRange priceRange() {
        return priceRange;
    }

    public Integer partySize() {
        return partySize;
    }

    public LocalDate reservationDate() {
        return reservationDate;
    }

    public LocalTime reservationTime() {
        return reservationTime;
    }

    public String remainingKeyword() {
        return remainingKeyword;
    }

    public IntegratedStoreSearchSort sort() {
        return sort;
    }

    public int size() {
        return size;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public Optional<IntegratedSearchCursor> cursor() {
        return Optional.ofNullable(cursor);
    }
}
