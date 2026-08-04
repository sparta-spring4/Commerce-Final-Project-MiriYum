package com.miriyum.domain.store.search.interpreter;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 외부 AI나 조회 계층 없이 검색 원문을 허용 조건과 키워드로 결정적으로 해석한다. */
public final class RuleInterpreter {

    public static final String RULE_VERSION = "rule-v1";

    private final Clock clock;

    /**
     * 상대 날짜 판정에 사용할 시계를 주입한다.
     *
     * @param clock 테스트와 운영에서 명시적으로 선택한 시계
     */
    public RuleInterpreter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 승인된 조건만 추출하고 나머지 입력은 일반 키워드로 보존한다.
     *
     * @param request 원문·사전·시간대 스냅샷
     * @return 규칙·사전 버전을 포함한 결정적 해석 결과
     * @throws IllegalArgumentException 원문이 {@code null}인 경우
     */
    public InterpretationResult interpret(InterpretationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String normalized = SearchInputNormalizer.normalize(request.rawInput());
        List<MatchedToken<String>> regionTokens =
                DictionaryMatcher.match(normalized, request.vocabulary().regions());
        List<MatchedToken<String>> storeCategoryTokens =
                DictionaryMatcher.match(normalized, request.vocabulary().storeCategories());
        List<MatchedToken<String>> menuCategoryTokens =
                DictionaryMatcher.match(normalized, request.vocabulary().menuCategories());
        List<MatchedToken<String>> tagTokens =
                DictionaryMatcher.match(normalized, request.vocabulary().tags());

        DictionaryResolution dictionary = resolveDictionaryTokens(
                regionTokens,
                storeCategoryTokens,
                menuCategoryTokens,
                tagTokens);
        PriceParser.Result price = PriceParser.parse(normalized);
        PartySizeParser.Result partySize = PartySizeParser.parse(normalized);
        DateParser.Result date = DateParser.parse(normalized, clock, request.zoneId());
        TimeParser.Result time = TimeParser.parse(normalized);
        CrossFieldResolution cross = resolveDictionaryStructuredOverlaps(
                dictionary,
                price.acceptedSpans(),
                partySize.acceptedSpans(),
                date.acceptedSpans(),
                time.acceptedSpans());
        dictionary = cross.dictionary();

        List<TextSpan> acceptedSpans = new ArrayList<>();
        acceptedSpans.addAll(spansOf(dictionary.regions()));
        acceptedSpans.addAll(spansOf(dictionary.storeCategories()));
        acceptedSpans.addAll(spansOf(dictionary.menuCategories()));
        acceptedSpans.addAll(spansOf(dictionary.tags()));
        if (!cross.rejectPrice()) {
            acceptedSpans.addAll(price.acceptedSpans());
        }
        if (!cross.rejectPartySize()) {
            acceptedSpans.addAll(partySize.acceptedSpans());
        }
        if (!cross.rejectDate()) {
            acceptedSpans.addAll(date.acceptedSpans());
        }
        if (!cross.rejectTime()) {
            acceptedSpans.addAll(time.acceptedSpans());
        }
        List<InterpretationWarning> warnings = new ArrayList<>();
        if (dictionary.ambiguous() || cross.ambiguous()) {
            warnings.add(new InterpretationWarning(
                    WarningCode.AMBIGUOUS_DICTIONARY_TERM,
                    WarningField.DICTIONARY));
        }
        warnings.addAll(price.warnings());
        warnings.addAll(partySize.warnings());
        warnings.addAll(date.warnings());
        warnings.addAll(time.warnings());
        InterpretedSearchCondition condition = new InterpretedSearchCondition(
                codesOf(dictionary.regions()),
                codesOf(dictionary.storeCategories()),
                codesOf(dictionary.menuCategories()),
                codesOf(dictionary.tags()),
                cross.rejectPrice() ? null : price.value(),
                cross.rejectPartySize() ? null : partySize.value(),
                cross.rejectDate() ? null : date.value(),
                cross.rejectTime() ? null : time.value(),
                removeAcceptedSpans(normalized, acceptedSpans));
        return new InterpretationResult(
                RULE_VERSION,
                request.vocabulary().version(),
                condition,
                warnings);
    }

    private static CrossFieldResolution resolveDictionaryStructuredOverlaps(
            DictionaryResolution dictionary,
            List<TextSpan> priceSpans,
            List<TextSpan> partySizeSpans,
            List<TextSpan> dateSpans,
            List<TextSpan> timeSpans) {
        List<MatchedToken<String>> dictionaryTokens = new ArrayList<>();
        dictionaryTokens.addAll(dictionary.regions());
        dictionaryTokens.addAll(dictionary.storeCategories());
        dictionaryTokens.addAll(dictionary.menuCategories());
        dictionaryTokens.addAll(dictionary.tags());

        List<TextSpan> structuredSpans = new ArrayList<>();
        structuredSpans.addAll(priceSpans);
        structuredSpans.addAll(partySizeSpans);
        structuredSpans.addAll(dateSpans);
        structuredSpans.addAll(timeSpans);

        Set<MatchedToken<String>> rejectedDictionary = new HashSet<>();
        for (MatchedToken<String> token : dictionaryTokens) {
            if (structuredSpans.stream().anyMatch(token.span()::overlaps)) {
                rejectedDictionary.add(token);
            }
        }
        boolean rejectPrice = overlapsDictionary(priceSpans, dictionaryTokens);
        boolean rejectPartySize = overlapsDictionary(partySizeSpans, dictionaryTokens);
        boolean rejectDate = overlapsDictionary(dateSpans, dictionaryTokens);
        boolean rejectTime = overlapsDictionary(timeSpans, dictionaryTokens);
        boolean ambiguous = !rejectedDictionary.isEmpty();

        DictionaryResolution resolvedDictionary = new DictionaryResolution(
                withoutRejected(dictionary.regions(), rejectedDictionary),
                withoutRejected(dictionary.storeCategories(), rejectedDictionary),
                withoutRejected(dictionary.menuCategories(), rejectedDictionary),
                withoutRejected(dictionary.tags(), rejectedDictionary),
                dictionary.ambiguous());
        return new CrossFieldResolution(
                resolvedDictionary,
                rejectPrice,
                rejectPartySize,
                rejectDate,
                rejectTime,
                ambiguous);
    }

    private static boolean overlapsDictionary(
            List<TextSpan> spans,
            List<MatchedToken<String>> dictionaryTokens) {
        return spans.stream().anyMatch(span -> dictionaryTokens.stream()
                .anyMatch(token -> span.overlaps(token.span())));
    }

    private static DictionaryResolution resolveDictionaryTokens(
            List<MatchedToken<String>> regions,
            List<MatchedToken<String>> storeCategories,
            List<MatchedToken<String>> menuCategories,
            List<MatchedToken<String>> tags) {
        List<List<MatchedToken<String>>> groups =
                List.of(regions, storeCategories, menuCategories, tags);
        Set<MatchedToken<String>> rejected = new HashSet<>();
        for (int leftGroup = 0; leftGroup < groups.size(); leftGroup++) {
            for (int rightGroup = leftGroup + 1; rightGroup < groups.size(); rightGroup++) {
                for (MatchedToken<String> left : groups.get(leftGroup)) {
                    for (MatchedToken<String> right : groups.get(rightGroup)) {
                        if (left.span().overlaps(right.span())) {
                            rejected.add(left);
                            rejected.add(right);
                        }
                    }
                }
            }
        }
        return new DictionaryResolution(
                withoutRejected(regions, rejected),
                withoutRejected(storeCategories, rejected),
                withoutRejected(menuCategories, rejected),
                withoutRejected(tags, rejected),
                !rejected.isEmpty());
    }

    private static List<MatchedToken<String>> withoutRejected(
            List<MatchedToken<String>> tokens,
            Set<MatchedToken<String>> rejected) {
        return tokens.stream().filter(token -> !rejected.contains(token)).toList();
    }

    private static List<String> codesOf(List<MatchedToken<String>> tokens) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        tokens.forEach(token -> codes.add(token.value()));
        return List.copyOf(codes);
    }

    private static List<TextSpan> spansOf(List<? extends MatchedToken<?>> tokens) {
        return tokens.stream().map(MatchedToken::span).toList();
    }

    private static String removeAcceptedSpans(String input, List<TextSpan> spans) {
        StringBuilder remaining = new StringBuilder(input);
        for (TextSpan span : spans) {
            for (int index = span.startInclusive(); index < span.endExclusive(); index++) {
                remaining.setCharAt(index, ' ');
            }
        }
        return SearchInputNormalizer.normalize(remaining.toString());
    }

    private record DictionaryResolution(
            List<MatchedToken<String>> regions,
            List<MatchedToken<String>> storeCategories,
            List<MatchedToken<String>> menuCategories,
            List<MatchedToken<String>> tags,
            boolean ambiguous) {
    }

    private record CrossFieldResolution(
            DictionaryResolution dictionary,
            boolean rejectPrice,
            boolean rejectPartySize,
            boolean rejectDate,
            boolean rejectTime,
            boolean ambiguous) {
    }
}
