package com.miriyum.domain.store.search.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class DictionaryMatcher {

    private DictionaryMatcher() {
    }

    static List<MatchedToken<String>> match(
            String normalizedInput,
            List<VocabularyEntry> entries) {
        List<MatchedToken<String>> candidates = new ArrayList<>();
        for (VocabularyEntry entry : entries) {
            for (String rawAlias : entry.aliases()) {
                String alias = SearchInputNormalizer.normalize(rawAlias);
                collectMatches(normalizedInput, alias, entry.code(), candidates);
            }
        }

        candidates.sort(Comparator
                .comparingInt((MatchedToken<String> token) -> token.span().length())
                .reversed()
                .thenComparingInt(token -> token.span().startInclusive()));

        List<MatchedToken<String>> selected = new ArrayList<>();
        for (MatchedToken<String> candidate : candidates) {
            if (selected.stream().noneMatch(token -> token.span().overlaps(candidate.span()))) {
                selected.add(candidate);
            }
        }
        selected.sort(Comparator.comparingInt(token -> token.span().startInclusive()));
        return List.copyOf(selected);
    }

    private static void collectMatches(
            String input,
            String alias,
            String code,
            List<MatchedToken<String>> candidates) {
        for (int start = 0; start + alias.length() <= input.length(); start++) {
            if (!input.regionMatches(true, start, alias, 0, alias.length())) {
                continue;
            }
            int end = start + alias.length();
            if (hasIndependentBoundaries(input, start, end)) {
                candidates.add(new MatchedToken<>(code, new TextSpan(start, end)));
            }
        }
    }

    private static boolean hasIndependentBoundaries(String input, int start, int end) {
        boolean leftIndependent = start == 0
                || !Character.isLetterOrDigit(input.codePointBefore(start));
        boolean rightIndependent = end == input.length()
                || !Character.isLetterOrDigit(input.codePointAt(end));
        return leftIndependent && rightIndependent;
    }
}
