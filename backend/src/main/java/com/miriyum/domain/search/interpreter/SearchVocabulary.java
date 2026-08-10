package com.miriyum.domain.search.interpreter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 호출자가 승인한 검색 사전 스냅샷이다.
 *
 * @param version 사전 내용과 함께 변경되는 버전
 * @param regions 지역 코드와 별칭
 * @param storeCategories 매장 카테고리 코드와 별칭
 * @param menuCategories 메뉴 카테고리 코드와 별칭
 * @param tags 검색 태그 코드와 별칭
 */
public record SearchVocabulary(
        String version,
        List<VocabularyEntry> regions,
        List<VocabularyEntry> storeCategories,
        List<VocabularyEntry> menuCategories,
        List<VocabularyEntry> tags) {

    public SearchVocabulary {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        regions = copyRequired(regions, "regions");
        storeCategories = copyRequired(storeCategories, "storeCategories");
        menuCategories = copyRequired(menuCategories, "menuCategories");
        tags = copyRequired(tags, "tags");
    }

    private static List<VocabularyEntry> copyRequired(
            List<VocabularyEntry> entries,
            String fieldName) {
        if (entries == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        List<VocabularyEntry> copied = List.copyOf(entries);
        validateUniqueEntries(copied, fieldName);
        return copied;
    }

    private static void validateUniqueEntries(
            List<VocabularyEntry> entries,
            String fieldName) {
        Set<String> codes = new HashSet<>();
        List<String> normalizedAliases = new ArrayList<>();
        for (VocabularyEntry entry : entries) {
            if (!codes.add(entry.code())) {
                throw new IllegalArgumentException(fieldName + " contains duplicate code");
            }
            for (String alias : entry.aliases()) {
                String normalizedAlias = normalizeAlias(alias);
                if (normalizedAlias.isEmpty()) {
                    throw new IllegalArgumentException(
                            fieldName + " contains alias that normalizes to empty");
                }
                if (normalizedAliases.stream()
                        .anyMatch(existing -> existing.equalsIgnoreCase(normalizedAlias))) {
                    throw new IllegalArgumentException(fieldName + " contains duplicate alias");
                }
                normalizedAliases.add(normalizedAlias);
            }
        }
    }

    private static String normalizeAlias(String alias) {
        return SearchInputNormalizer.normalize(alias);
    }
}
