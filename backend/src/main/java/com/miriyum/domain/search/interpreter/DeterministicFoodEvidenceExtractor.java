package com.miriyum.domain.search.interpreter;

import com.miriyum.domain.search.expansion.StructuredFoodEvidence;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.Dimension;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.EvidenceTerm;
import com.miriyum.domain.search.expansion.StructuredFoodEvidenceSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 원문에서 검토된 가장 긴 음식 표현을 차원별로 먼저 추출한다. */
@Component
public final class DeterministicFoodEvidenceExtractor {

    private static final List<Dimension> EXTRACTED_DIMENSIONS = List.of(
            Dimension.MENU_FAMILY,
            Dimension.INGREDIENT,
            Dimension.TASTE,
            Dimension.BROTH,
            Dimension.METHOD,
            Dimension.AROMA,
            Dimension.TEXTURE,
            Dimension.FORM);

    private final FoodEvidenceVocabulary vocabulary;

    public DeterministicFoodEvidenceExtractor(FoodEvidenceVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }

    public StructuredFoodEvidence extract(String input) {
        if (input == null || input.isBlank()) {
            return StructuredFoodEvidence.empty();
        }
        String normalized = input.trim().replaceAll("\\s+", " ");
        Map<Dimension, List<EvidenceTerm>> extracted = new EnumMap<>(Dimension.class);
        for (Dimension dimension : EXTRACTED_DIMENSIONS) {
            extracted.put(dimension, extractDimension(normalized, dimension));
        }
        StructuredFoodEvidence withoutRaw = new StructuredFoodEvidence(
                List.of(),
                extracted.get(Dimension.MENU_FAMILY),
                extracted.get(Dimension.INGREDIENT),
                extracted.get(Dimension.TASTE),
                extracted.get(Dimension.BROTH),
                extracted.get(Dimension.METHOD),
                extracted.get(Dimension.AROMA),
                extracted.get(Dimension.TEXTURE),
                extracted.get(Dimension.FORM));
        List<EvidenceTerm> raw = !withoutRaw.menuFamilies().isEmpty()
                || withoutRaw.coreDimensionTerms().size() >= 2
                ? List.of(new EvidenceTerm(
                        "RAW_FOOD_SPAN",
                        normalized,
                        List.of(normalized),
                        StructuredFoodEvidenceSource.DETERMINISTIC))
                : List.of();
        return new StructuredFoodEvidence(
                raw,
                withoutRaw.menuFamilies(),
                withoutRaw.ingredients(),
                withoutRaw.tastes(),
                withoutRaw.broths(),
                withoutRaw.methods(),
                withoutRaw.aromas(),
                withoutRaw.textures(),
                withoutRaw.forms());
    }

    private List<EvidenceTerm> extractDimension(String input, Dimension dimension) {
        List<AliasCandidate> candidates = new ArrayList<>();
        for (FoodEvidenceVocabulary.Entry entry : vocabulary.entries(dimension)) {
            for (String alias : entry.aliases()) {
                int start = input.toLowerCase(Locale.ROOT)
                        .indexOf(alias.toLowerCase(Locale.ROOT));
                while (start >= 0) {
                    candidates.add(new AliasCandidate(entry, alias, start));
                    start = input.toLowerCase(Locale.ROOT)
                            .indexOf(alias.toLowerCase(Locale.ROOT), start + 1);
                }
            }
        }
        candidates.sort(Comparator
                .comparingInt(AliasCandidate::length).reversed()
                .thenComparingInt(AliasCandidate::start)
                .thenComparing(candidate -> candidate.entry().id()));
        boolean[] occupied = new boolean[input.length()];
        Map<String, EvidenceTerm> accepted = new LinkedHashMap<>();
        for (AliasCandidate candidate : candidates) {
            if (overlaps(occupied, candidate.start(), candidate.end())) {
                continue;
            }
            accepted.putIfAbsent(
                    candidate.entry().id(),
                    candidate.entry().toTerm(
                            candidate.alias(),
                            StructuredFoodEvidenceSource.DETERMINISTIC));
            mark(occupied, candidate.start(), candidate.end());
        }
        return List.copyOf(accepted.values());
    }

    private static boolean overlaps(boolean[] occupied, int start, int end) {
        for (int index = start; index < end; index++) {
            if (occupied[index]) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] occupied, int start, int end) {
        for (int index = start; index < end; index++) {
            occupied[index] = true;
        }
    }

    private record AliasCandidate(
            FoodEvidenceVocabulary.Entry entry,
            String alias,
            int start
    ) {
        int end() {
            return start + alias.length();
        }

        int length() {
            return alias.length();
        }
    }
}
