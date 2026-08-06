package com.miriyum.domain.store.search.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.search.interpreter.InterpretationWarning;
import com.miriyum.domain.store.search.interpreter.WarningCode;
import com.miriyum.domain.store.search.interpreter.WarningField;
import com.miriyum.domain.store.recommendation.ranking.RecommendationReason;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntegratedStoreSearchDataTest {

    @Test
    void copiesCollectionsAndAllowsMissingCoordinates() {
        List<IntegratedStoreSearchItem> items = new ArrayList<>(List.of(item(null)));
        List<InterpretationWarning> warnings = new ArrayList<>(List.of(
                new InterpretationWarning(
                        WarningCode.AMBIGUOUS_TIME,
                        WarningField.TIME)));
        NormalizedSearchCondition condition = new NormalizedSearchCondition(
                List.of("SEOUL"), List.of("KOREAN"), List.of(), List.of(),
                10_000L, 20_000L, 2,
                LocalDate.of(2026, 8, 7), LocalTime.of(18, 0), "조용한");

        IntegratedStoreSearchData data = new IntegratedStoreSearchData(
                items, condition, warnings, "rule-v1", "catalog-v1", "history-v1", null);
        items.clear();
        warnings.clear();

        assertThat(data.items()).hasSize(1);
        assertThat(data.items().getFirst().coordinates()).isNull();
        assertThat(data.warnings()).hasSize(1);
        assertThat(data.rankingRuleVersion()).isEqualTo("history-v1");
        assertThatThrownBy(() -> data.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void coordinatesRequireBothValues() {
        assertThatThrownBy(() -> new PublicStoreCoordinates(BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static IntegratedStoreSearchItem item(PublicStoreCoordinates coordinates) {
        return new IntegratedStoreSearchItem(
                "7", "미리윰", Region.SEOUL, "서울 중구", "KOREAN",
                OperationStatus.OPEN,
                new PublicStoreModes(true, true, false),
                ReservationAvailability.AVAILABLE,
                coordinates,
                RecommendationReason.ORDERED_MENU);
    }
}
