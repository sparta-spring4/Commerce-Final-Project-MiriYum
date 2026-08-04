package com.miriyum.domain.store.search.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class DictionaryMatcher {

    private DictionaryMatcher() {
    }

    static List<MatchedToken<String>> match(
            String normalizedInput,
            List<VocabularyEntry> entries) {
        String comparisonInput = normalizedInput.toLowerCase(Locale.ROOT);
        List<MatchedToken<String>> candidates = new ArrayList<>();
        for (VocabularyEntry entry : entries) {
            for (String rawAlias : entry.aliases()) {
                String alias = SearchInputNormalizer.normalize(rawAlias).toLowerCase(Locale.ROOT);
                collectMatches(comparisonInput, alias, entry.code(), candidates);
            }
        }

        candidates.sort(Comparator
                .comparingInt((MatchedToken<String> token) -> token.span().startInclusive())
                .thenComparing(Comparator.comparingInt(
                        (MatchedToken<String> token) -> token.span().length()).reversed()));

        List<MatchedToken<String>> selected = new ArrayList<>();
        for (MatchedToken<String> candidate : candidates) {
            if (selected.stream().noneMatch(token -> token.span().overlaps(candidate.span()))) {
                selected.add(candidate);
            }
        }
        return List.copyOf(selected);
    }

    private static void collectMatches(
            String comparisonInput,
            String alias,
            String code,
            List<MatchedToken<String>> candidates) {
        int fromIndex = 0;
        while (fromIndex < comparisonInput.length()) {
            int start = comparisonInput.indexOf(alias, fromIndex);
            if (start < 0) {
                return;
            }
            int end = start + alias.length();
            if (hasIndependentBoundaries(comparisonInput, start, end)) {
                candidates.add(new MatchedToken<>(code, new TextSpan(start, end)));
            }
            fromIndex = start + Math.max(1, alias.length());
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
