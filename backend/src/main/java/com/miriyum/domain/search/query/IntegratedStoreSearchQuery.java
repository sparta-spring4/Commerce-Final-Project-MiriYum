package com.miriyum.domain.search.query;

import com.miriyum.domain.search.interpreter.InterpretedSearchCondition;
import com.miriyum.domain.search.interpreter.PriceRange;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** #110의 허용 조건을 QueryDSL 조회와 seek cursor에 사용할 불변 입력으로 만든다. */
public final class IntegratedStoreSearchQuery {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final int MAX_LEXICAL_FOOD_TERMS = 20;
    private static final Pattern SEARCH_TOKEN = Pattern.compile("[0-9A-Za-z가-힣]+");
    private static final Set<String> LEXICAL_STOPWORDS = Set.of(
            "가게", "곳", "메뉴", "음식", "요리", "식사", "추천", "추천해줘",
            "찾아줘", "찾아", "에서", "으로", "만든", "같은", "있는", "나는",
            "맛에", "향이", "나고", "국물", "가격", "이하", "이상",
            "면", "탕", "국", "밥", "세트", "정식", "음료");

    private final List<String> regionCodes;
    private final List<String> storeCategoryCodes;
    private final List<String> menuCategoryCodes;
    private final List<String> tagCodes;
    private final PriceRange priceRange;
    private final Integer partySize;
    private final LocalDate reservationDate;
    private final LocalTime reservationTime;
    private final String remainingKeyword;
    private final List<String> lexicalFoodTerms;
    private final List<String> explicitMenuNames;
    private final StructuredFoodEvidence foodEvidence;
    private final IntegratedStoreSearchSort sort;
    private final int size;
    private final String fingerprint;
    private final IntegratedSearchCursor cursor;

    private IntegratedStoreSearchQuery(
            InterpretedSearchCondition condition,
            List<String> explicitMenuNames,
            StructuredFoodEvidence foodEvidence,
            boolean includesInfants,
            boolean availableOnly,
            String principalScope,
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
        this.lexicalFoodTerms = lexicalFoodTerms(remainingKeyword);
        this.explicitMenuNames = canonicalMenuNames(explicitMenuNames);
        this.foodEvidence = Objects.requireNonNull(
                foodEvidence, "foodEvidence must not be null");
        this.sort = sort;
        this.size = size;
        Objects.requireNonNull(principalScope, "principalScope must not be null");
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
                includesInfants,
                availableOnly,
                principalScope,
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
            List<String> explicitMenuNames,
            StructuredFoodEvidence foodEvidence,
            boolean includesInfants,
            boolean availableOnly,
            String principalScope,
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
                explicitMenuNames,
                foodEvidence,
                includesInfants,
                availableOnly,
                principalScope,
                IntegratedStoreSearchSort.parse(sort),
                cursor,
                resolvedSize,
                cursorCodec);
    }

    public static IntegratedStoreSearchQuery from(
            InterpretedSearchCondition condition,
            List<String> explicitMenuNames,
            boolean includesInfants,
            boolean availableOnly,
            String principalScope,
            String sort,
            String cursor,
            Integer size,
            IntegratedSearchCursorCodec cursorCodec
    ) {
        return from(
                condition,
                explicitMenuNames,
                StructuredFoodEvidence.empty(),
                includesInfants,
                availableOnly,
                principalScope,
                sort,
                cursor,
                size,
                cursorCodec);
    }

    public static IntegratedStoreSearchQuery from(
            InterpretedSearchCondition condition,
            boolean includesInfants,
            boolean availableOnly,
            String principalScope,
            String sort,
            String cursor,
            Integer size,
            IntegratedSearchCursorCodec cursorCodec
    ) {
        return from(
                condition,
                List.of(),
                StructuredFoodEvidence.empty(),
                includesInfants,
                availableOnly,
                principalScope,
                sort,
                cursor,
                size,
                cursorCodec);
    }

    public static IntegratedStoreSearchQuery from(
            InterpretedSearchCondition condition,
            List<String> explicitMenuNames,
            String sort,
            String cursor,
            Integer size,
            IntegratedSearchCursorCodec cursorCodec
    ) {
        return from(
                condition,
                explicitMenuNames,
                false,
                false,
                cursorCodec.principalScope(null),
                sort,
                cursor,
                size,
                cursorCodec);
    }

    public static IntegratedStoreSearchQuery from(
            InterpretedSearchCondition condition,
            String sort,
            String cursor,
            Integer size,
            IntegratedSearchCursorCodec cursorCodec
    ) {
        return from(
                condition,
                false,
                false,
                cursorCodec.principalScope(null),
                sort,
                cursor,
                size,
                cursorCodec);
    }

    private static List<String> canonicalCodes(List<String> values) {
        if (values == null || values.stream().anyMatch(
                value -> value == null || value.isBlank() || !value.equals(value.trim()))) {
            throw validationFailed();
        }
        return values.stream().distinct().sorted().toList();
    }

    private static List<String> canonicalMenuNames(List<String> values) {
        if (values == null || values.stream().anyMatch(
                value -> value == null || value.isBlank() || !value.equals(value.trim()))) {
            throw validationFailed();
        }
        return values.stream().distinct().sorted().toList();
    }

    private static List<String> lexicalFoodTerms(String keyword) {
        return SEARCH_TOKEN.matcher(keyword)
                .results()
                .map(match -> match.group().toLowerCase(java.util.Locale.ROOT))
                .filter(token -> token.codePointCount(0, token.length()) >= 2)
                .filter(token -> !LEXICAL_STOPWORDS.contains(token))
                .filter(token -> !token.chars().allMatch(Character::isDigit))
                .distinct()
                .limit(MAX_LEXICAL_FOOD_TERMS)
                .toList();
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
                || sort == IntegratedStoreSearchSort.RECOMMENDATION_DESC
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

    public List<String> lexicalFoodTerms() {
        return lexicalFoodTerms;
    }

    public List<String> explicitMenuNames() {
        return explicitMenuNames;
    }

    public StructuredFoodEvidence foodEvidence() {
        return foodEvidence;
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
