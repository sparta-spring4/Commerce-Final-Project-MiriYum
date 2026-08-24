package com.miriyum.domain.search.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.entity.QStore;
import com.miriyum.domain.search.interpreter.InterpretedSearchCondition;
import com.miriyum.domain.search.interpreter.PriceRange;
import com.miriyum.domain.search.query.IntegratedSearchCursorCodec;
import com.miriyum.domain.search.query.IntegratedStoreSearchQuery;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.HQLTemplates;
import com.querydsl.jpa.JPQLSerializer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IntegratedStoreSearchPredicatesTest {

    private static final IntegratedSearchCursorCodec CURSOR_CODEC =
            new IntegratedSearchCursorCodec(
                    "test-only-secret-key-must-be-at-least-32-bytes");

    @Test
    void assemblesOnlyApprovedPublicStoreAndMenuPredicates() {
        InterpretedSearchCondition condition = new InterpretedSearchCondition(
                List.of("SEOUL", "BUSAN"),
                List.of("KOREAN", "CAFE_BAKERY"),
                List.of("BEVERAGE", "DESSERT"),
                List.of("QUIET", "DATE"),
                new PriceRange(10_000L, 20_000L),
                null,
                null,
                null,
                "100%_특선!");
        IntegratedStoreSearchQuery query = IntegratedStoreSearchQuery.from(
                condition, null, null, 20, CURSOR_CODEC);

        RenderedPredicate rendered = render(
                IntegratedStoreSearchPredicates.create(QStore.store, query));

        assertThat(rendered.jpql())
                .contains("store.verificationStatus")
                .contains("store.operationStatus")
                .contains("store.region")
                .contains("store.storeCategoryCode")
                .contains("store.tagCodes")
                .contains("filteredMenu.retired")
                .contains("filteredMenu.visibility")
                .contains("filteredMenuVersion.status")
                .contains("filteredMenuVersion.primaryCategoryCode")
                .contains(".secondaryCategoryCodes")
                .contains("filteredMenuVersion.price >=")
                .contains("filteredMenuVersion.price <=")
                .contains("escape '!'")
                .doesNotContain(".sellingStatus");
        assertThat(rendered.constants())
                .contains("APPROVED", "CLOSED", "VISIBLE", "PUBLISHED")
                .doesNotContain("SELLING")
                .contains("%100!%!_특선!!%", "10000", "20000");
    }

    @Test
    void omitsMenuSubqueriesWhenNoMenuOrKeywordConditionExists() {
        InterpretedSearchCondition condition = new InterpretedSearchCondition(
                List.of("SEOUL"), List.of(), List.of(), List.of(), null,
                null, null, null, "");
        IntegratedStoreSearchQuery query = IntegratedStoreSearchQuery.from(
                condition, null, null, 20, CURSOR_CODEC);

        RenderedPredicate rendered = render(
                IntegratedStoreSearchPredicates.create(QStore.store, query));

        assertThat(rendered.jpql())
                .contains("store.verificationStatus")
                .doesNotContain("MenuVersion")
                .doesNotContain("keywordMenu")
                .doesNotContain("filteredMenu");
    }

    @Test
    void expandedMenuPredicateBindsCompoundConceptForReverseNameMatching() {
        InterpretedSearchCondition condition = new InterpretedSearchCondition(
                List.of("SEOUL"), List.of(), List.of("KOREAN"), List.of(),
                new PriceRange(8_000L, 15_000L), null, null, null, "");
        IntegratedStoreSearchQuery query = IntegratedStoreSearchQuery.from(
                condition, null, null, 20, CURSOR_CODEC);

        RenderedPredicate forward = render(
                IntegratedStoreSearchPredicates.createExpandedForward(
                        QStore.store, query, List.of("불향 해물 짬뽕")));
        RenderedPredicate reverse = render(
                IntegratedStoreSearchPredicates.createExpandedReverse(
                        QStore.store, query, List.of("불향 해물 짬뽕")));

        assertThat(forward.jpql())
                .contains("lower(expandedForwardMenuVersion.description) like")
                .contains("expandedForwardMenuVersion.primaryCategoryCode")
                .contains("expandedForwardMenuVersion.price >=")
                .contains("expandedForwardMenuVersion.price <=");
        assertThat(forward.constants())
                .contains("%불향 해물 짬뽕%", "8000", "15000");
        assertThat(reverse.jpql())
                .contains("locate(lower(expandedReverseMenuVersion.name)")
                .contains("length(trim(expandedReverseMenuVersion.name)) >=")
                .contains("trim(expandedReverseMenuVersion.name) not in")
                .contains("expandedReverseMenuVersion.primaryCategoryCode")
                .contains("expandedReverseMenuVersion.price >=")
                .contains("expandedReverseMenuVersion.price <=")
                .doesNotContain("locate(lower(expandedReverseMenuVersion.description)")
                .doesNotContain("locate(lower(expandedReverseMenuVersion.primaryCategoryCode)")
                .doesNotContain("locate(lower(expandedReverseMenuVersion_secondaryCategoryCodes")
                .doesNotContain("locate(lower(expandedReverseMenuVersion_localTags");
        assertThat(reverse.constants())
                .contains("불향 해물 짬뽕", "8000", "15000");
    }

    @Test
    void originalKeywordPredicateFindsGuardedMenuNameInsideNaturalLanguage() {
        InterpretedSearchCondition condition = new InterpretedSearchCondition(
                List.of(), List.of(), List.of(), List.of(), null,
                null, null, null, "짬뽕 파는 매장 중 추천순으로 보여줘");
        IntegratedStoreSearchQuery query = IntegratedStoreSearchQuery.from(
                condition,
                List.of("짬뽕"),
                null,
                null,
                20,
                CURSOR_CODEC);

        RenderedPredicate rendered = render(
                IntegratedStoreSearchPredicates.create(QStore.store, query));

        assertThat(rendered.jpql())
                .contains("trim(keywordMenuVersion.name) =")
                .doesNotContain("locate(lower(keywordMenuVersion.name)")
                .doesNotContain("moreSpecificKeywordMenuVersion");
        assertThat(rendered.constants())
                .contains("%짬뽕 파는 매장 중 추천순으로 보여줘%", "짬뽕");
    }

    @Test
    void reverseMenuGuardRejectsEveryGenericNameAndOneCharacterNames() {
        assertThat(List.of(
                "면", "탕", "국", "밥", "메뉴", "음식", "요리", "식사",
                "세트", "정식", "음료", "A"))
                .allMatch(name -> !IntegratedStoreSearchPredicates
                        .isEligibleReverseMenuName(name));
        assertThat(IntegratedStoreSearchPredicates.isEligibleReverseMenuName("국밥"))
                .isTrue();
        assertThat(IntegratedStoreSearchPredicates.isEligibleReverseMenuName(" 국밥 "))
                .isTrue();
        assertThat(IntegratedStoreSearchPredicates.isEligibleReverseMenuName("😀"))
                .isFalse();
        assertThat(IntegratedStoreSearchPredicates.isEligibleReverseMenuName("😀국"))
                .isTrue();
    }

    @Test
    void mostSpecificNamesAreFilteredBeforeFinalBound() {
        List<String> contained = new ArrayList<>();
        for (int length = 2; length <= 100; length++) {
            contained.add("가".repeat(length));
        }
        contained.add("나다");
        contained.add("라마");

        assertThat(IntegratedStoreSearchRepository.mostSpecificNames(contained, 100))
                .containsExactly("가".repeat(100), "나다", "라마");
    }

    @Test
    void substringLookupCandidatesAreDeterministicAndInputBounded() {
        String keyword = "가".repeat(98) + "짬뽕";

        List<String> candidates = IntegratedStoreSearchRepository
                .candidateMenuNames(keyword);

        assertThat(candidates).contains("짬뽕", "가짬뽕");
        assertThat(candidates).doesNotContain("면", "탕", "국", "밥");
        assertThat(candidates).hasSizeLessThanOrEqualTo(4_950);
        assertThat(candidates).isSorted();
    }

    @Test
    void mostSpecificNamesFollowAccentInsensitiveUnicodeNormalization() {
        assertThat(IntegratedStoreSearchRepository.mostSpecificNames(
                List.of("Café", "Cafe\u0301 Latte"), 100))
                .containsExactly("Cafe\u0301 Latte");

        List<String> supplementary = IntegratedStoreSearchRepository
                .candidateMenuNames("😀국 추천");
        assertThat(supplementary).contains("😀국").doesNotContain("😀");
    }

    private static RenderedPredicate render(BooleanBuilder predicate) {
        JPQLSerializer serializer = new JPQLSerializer(HQLTemplates.DEFAULT);
        serializer.handle(predicate.getValue());
        Set<String> constants = serializer.getConstantToLabel().keySet().stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
        return new RenderedPredicate(serializer.toString(), constants);
    }

    private record RenderedPredicate(String jpql, Set<String> constants) {
    }
}
