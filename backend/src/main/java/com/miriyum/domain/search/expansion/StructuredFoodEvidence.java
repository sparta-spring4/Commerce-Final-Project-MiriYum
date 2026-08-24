package com.miriyum.domain.search.expansion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 메뉴 계열과 서로 다른 음식 속성을 출처와 함께 보존하는 내부 검색 근거다. */
public record StructuredFoodEvidence(
        List<EvidenceTerm> rawFoodSpans,
        List<EvidenceTerm> menuFamilies,
        List<EvidenceTerm> ingredients,
        List<EvidenceTerm> tastes,
        List<EvidenceTerm> broths,
        List<EvidenceTerm> methods,
        List<EvidenceTerm> aromas,
        List<EvidenceTerm> textures,
        List<EvidenceTerm> forms
) {

    private static final List<Dimension> CORE_DIMENSIONS = List.of(
            Dimension.INGREDIENT,
            Dimension.TASTE,
            Dimension.BROTH,
            Dimension.METHOD,
            Dimension.AROMA,
            Dimension.TEXTURE);

    public StructuredFoodEvidence {
        rawFoodSpans = copyDistinct(rawFoodSpans, "rawFoodSpans");
        menuFamilies = copyDistinct(menuFamilies, "menuFamilies");
        ingredients = copyDistinct(ingredients, "ingredients");
        tastes = copyDistinct(tastes, "tastes");
        broths = copyDistinct(broths, "broths");
        methods = copyDistinct(methods, "methods");
        aromas = copyDistinct(aromas, "aromas");
        textures = copyDistinct(textures, "textures");
        forms = copyDistinct(forms, "forms");
    }

    public static StructuredFoodEvidence empty() {
        return new StructuredFoodEvidence(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    /** 이미 결정된 차원은 유지하고 비어 있는 차원만 보충한다. */
    public StructuredFoodEvidence fillEmptyDimensionsFrom(
            StructuredFoodEvidence supplement
    ) {
        Objects.requireNonNull(supplement, "supplement must not be null");
        return new StructuredFoodEvidence(
                choose(rawFoodSpans, supplement.rawFoodSpans),
                choose(menuFamilies, supplement.menuFamilies),
                choose(ingredients, supplement.ingredients),
                choose(tastes, supplement.tastes),
                choose(broths, supplement.broths),
                choose(methods, supplement.methods),
                choose(aromas, supplement.aromas),
                choose(textures, supplement.textures),
                choose(forms, supplement.forms));
    }

    public boolean hasCandidateEvidence() {
        return rawFoodSpans.stream().anyMatch(
                        term -> term.source() == StructuredFoodEvidenceSource.DETERMINISTIC)
                || !menuFamilies.isEmpty()
                || coreDimensionTerms().size() >= 2;
    }

    public List<String> deterministicMenuTerms() {
        return menuTerms(StructuredFoodEvidenceSource.DETERMINISTIC);
    }

    public List<String> llmMenuTerms() {
        return menuTerms(StructuredFoodEvidenceSource.LLM);
    }

    public Map<Dimension, List<String>> coreDimensionTerms() {
        Map<Dimension, List<String>> result = new EnumMap<>(Dimension.class);
        for (Dimension dimension : CORE_DIMENSIONS) {
            List<String> terms = terms(dimension).stream()
                    .flatMap(term -> term.matchTerms().stream())
                    .distinct()
                    .toList();
            if (!terms.isEmpty()) {
                result.put(dimension, terms);
            }
        }
        return Map.copyOf(result);
    }

    public List<EvidenceTerm> terms(Dimension dimension) {
        Objects.requireNonNull(dimension, "dimension must not be null");
        return switch (dimension) {
            case RAW_FOOD_SPAN -> rawFoodSpans;
            case MENU_FAMILY -> menuFamilies;
            case INGREDIENT -> ingredients;
            case TASTE -> tastes;
            case BROTH -> broths;
            case METHOD -> methods;
            case AROMA -> aromas;
            case TEXTURE -> textures;
            case FORM -> forms;
        };
    }

    private List<String> menuTerms(StructuredFoodEvidenceSource source) {
        Set<String> terms = new LinkedHashSet<>();
        if (source == StructuredFoodEvidenceSource.DETERMINISTIC) {
            rawFoodSpans.stream()
                    .filter(term -> term.source() == source)
                    .flatMap(term -> term.matchTerms().stream())
                    .forEach(terms::add);
        }
        menuFamilies.stream()
                .filter(term -> term.source() == source)
                .flatMap(term -> term.matchTerms().stream())
                .forEach(terms::add);
        return List.copyOf(terms);
    }

    private static List<EvidenceTerm> choose(
            List<EvidenceTerm> primary,
            List<EvidenceTerm> supplement
    ) {
        return primary.isEmpty() ? supplement : primary;
    }

    private static List<EvidenceTerm> copyDistinct(
            List<EvidenceTerm> values,
            String fieldName
    ) {
        if (values == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        Map<String, EvidenceTerm> distinct = new LinkedHashMap<>();
        for (EvidenceTerm value : values) {
            if (value == null) {
                throw new IllegalArgumentException(fieldName + " must not contain null");
            }
            distinct.putIfAbsent(value.id().toLowerCase(Locale.ROOT), value);
        }
        return List.copyOf(distinct.values());
    }

    public enum Dimension {
        RAW_FOOD_SPAN,
        MENU_FAMILY,
        INGREDIENT,
        TASTE,
        BROTH,
        METHOD,
        AROMA,
        TEXTURE,
        FORM
    }

    public record EvidenceTerm(
            String id,
            String surface,
            List<String> matchTerms,
            StructuredFoodEvidenceSource source
    ) {

        private static final int MAX_TERM_LENGTH = 60;

        public EvidenceTerm {
            id = normalizeRequired(id, "id");
            surface = normalizeRequired(surface, "surface");
            Objects.requireNonNull(source, "source must not be null");
            if (matchTerms == null) {
                throw new IllegalArgumentException("matchTerms must not be null");
            }
            List<String> normalized = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String value : matchTerms) {
                if (value == null) {
                    throw new IllegalArgumentException("matchTerms must not contain null");
                }
                String term = normalizeRequired(value, "matchTerm");
                if (seen.add(term.toLowerCase(Locale.ROOT))) {
                    normalized.add(term);
                }
            }
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("matchTerms must not be empty");
            }
            matchTerms = List.copyOf(normalized);
        }

        private static String normalizeRequired(String value, String fieldName) {
            if (value == null) {
                throw new IllegalArgumentException(fieldName + " must not be null");
            }
            String normalized = value.trim().replaceAll("\\s+", " ");
            if (normalized.isBlank() || normalized.length() > MAX_TERM_LENGTH) {
                throw new IllegalArgumentException(fieldName + " has invalid length");
            }
            return normalized;
        }
    }
}
