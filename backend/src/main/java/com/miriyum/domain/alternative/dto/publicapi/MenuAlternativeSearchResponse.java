package com.miriyum.domain.alternative.dto.publicapi;

import com.miriyum.domain.alternative.model.AlternativeReasonCode;
import com.miriyum.domain.alternative.model.MenuAlternativeMode;
import com.miriyum.domain.alternative.model.MenuAlternativeRankingReason;
import com.miriyum.domain.alternative.model.MenuAlternativeResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record MenuAlternativeSearchResponse(String sourceStoreId, String sourceMenuId,
        int quantity, OffsetDateTime startAt, OffsetDateTime serviceEndAt, String timeZoneId,
        MenuAlternativeMode mode, List<Item> items) {
    public MenuAlternativeSearchResponse { items = List.copyOf(items); }

    public static MenuAlternativeSearchResponse from(MenuAlternativeResult result) {
        return new MenuAlternativeSearchResponse(Long.toString(result.sourceStoreId()),
                Long.toString(result.sourceMenuId()), result.quantity(), result.startAt(),
                result.serviceEndAt(), result.timeZoneId(), result.mode(), result.items().stream()
                .map(value -> new Item(Long.toString(value.storeId()), value.storeName(),
                        Long.toString(value.menuId()), value.menuName(), value.unitPrice(),
                        value.availableOnlineQuantity(), value.secondaryCategoryMatchCount(),
                        value.distanceMeters(), value.latitude() == null ? null
                        : new Coordinates(value.latitude(), value.longitude()), value.reasonCodes(),
                        value.score().totalScore(), value.score().rankingReason(),
                        new ScoreBreakdown(value.score().conceptScore(),
                                value.score().secondaryCategoryScore(),
                                value.score().priceSimilarityScore())))
                .toList());
    }

    public record Item(String storeId, String storeName, String menuId, String menuName,
            int unitPrice, int availableOnlineQuantity, int secondaryCategoryMatchCount,
            BigDecimal distanceMeters, Coordinates coordinates,
            List<AlternativeReasonCode> reasonCodes, int alternativeScore,
            MenuAlternativeRankingReason rankingReason, ScoreBreakdown scoreBreakdown) {
        public Item { reasonCodes = List.copyOf(reasonCodes); }
    }
    public record Coordinates(BigDecimal latitude, BigDecimal longitude) {}
    public record ScoreBreakdown(
            int llmConcept,
            int secondaryCategory,
            int priceSimilarity
    ) {}
}
