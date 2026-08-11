package com.miriyum.domain.alternative.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.alternative.model.EligibleAlternative;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuAlternativeOrderingTest {

    @Test
    void sameStoreOrdersBySecondaryMatchesPriceDifferencePriceAndMenuId() {
        var values = List.of(item(1, 4, 12_000, 2, null), item(1, 3, 9_000, 2, null),
                item(1, 2, 11_000, 2, null), item(1, 1, 10_000, 1, null));
        assertThat(values.stream().sorted(MenuAlternativeOrdering.sameStoreComparator())
                .map(value -> value.candidate().menuId()))
                .containsExactly(3L, 2L, 4L, 1L);
    }

    @Test
    void nearbyOrdersByDistanceThenPolicySignalsAndIds() {
        var values = List.of(item(2, 2, 10_000, 2, new BigDecimal("20")),
                item(1, 3, 10_000, 2, new BigDecimal("10")),
                item(1, 1, 10_000, 1, new BigDecimal("10")));
        assertThat(values.stream().sorted(MenuAlternativeOrdering.nearbyStoreComparator())
                .map(value -> value.candidate().menuId()))
                .containsExactly(3L, 1L, 2L);
    }

    private EligibleAlternative item(long storeId, long menuId, int price, int matches,
            BigDecimal distance) {
        return EligibleAlternative.testValue(storeId, menuId, price, matches,
                Math.abs(price - 10_000), distance);
    }
}
