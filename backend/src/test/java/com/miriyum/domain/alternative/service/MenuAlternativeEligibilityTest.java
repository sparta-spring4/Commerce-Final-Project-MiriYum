package com.miriyum.domain.alternative.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.alternative.model.AlternativeMenuCandidate;
import com.miriyum.domain.alternative.model.AlternativeMenuSource;
import com.miriyum.domain.search.dto.contract.MenuAlternativeAllergenView;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MenuAlternativeEligibilityTest {

    private final MenuAlternativeEligibility policy = new MenuAlternativeEligibility();
    private final AlternativeMenuSource source = new AlternativeMenuSource(
            1L, 10L, 10_000, "MAIN", List.of("A", "B"));

    @Test
    void includesPricesAtExactlyEightyAndOneHundredTwentyPercent() {
        assertThat(policy.evaluate(source, candidate(2L, 8_000), Set.of())).isPresent();
        assertThat(policy.evaluate(source, candidate(3L, 12_000), Set.of())).isPresent();
    }

    @Test
    void excludesDifferentPrimaryCategoryAndOutOfRangePrices() {
        assertThat(policy.evaluate(source,
                candidate(2L, 10_000, "OTHER", "REGISTERED", List.of()), Set.of())).isEmpty();
        assertThat(policy.evaluate(source, candidate(3L, 7_999), Set.of())).isEmpty();
        assertThat(policy.evaluate(source, candidate(4L, 12_001), Set.of())).isEmpty();
    }

    @Test
    void excludesUnsafeOrUnregisteredAllergenInformation() {
        assertThat(policy.evaluate(source,
                candidate(2L, 10_000, "MAIN", "UNREGISTERED", List.of()),
                Set.of("MILK"))).isEmpty();
        assertThat(policy.evaluate(source,
                candidate(3L, 10_000, "MAIN", "REGISTERED", List.of(
                        new MenuAlternativeAllergenView("MILK", "MAY_CONTAIN"))),
                Set.of("MILK"))).isEmpty();
        assertThat(policy.evaluate(source,
                candidate(4L, 10_000, "MAIN", "REGISTERED", List.of()),
                Set.of("MILK"))).isPresent();
    }

    @Test
    void countsDistinctSecondaryCategoryIntersection() {
        var eligible = policy.evaluate(source, candidate(2L, 10_000), Set.of()).orElseThrow();
        assertThat(eligible.secondaryCategoryMatchCount()).isEqualTo(2);
    }

    private AlternativeMenuCandidate candidate(long menuId, int price) {
        return candidate(menuId, price, "MAIN", "REGISTERED", List.of());
    }

    private AlternativeMenuCandidate candidate(long menuId, int price, String primary,
            String registration, List<MenuAlternativeAllergenView> allergens) {
        return new AlternativeMenuCandidate(1L, "매장", menuId, "메뉴", price, primary,
                List.of("B", "A", "A"), registration, allergens, null, null, null);
    }
}
