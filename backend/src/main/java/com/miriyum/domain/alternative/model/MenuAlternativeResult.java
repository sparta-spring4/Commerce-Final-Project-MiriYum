package com.miriyum.domain.alternative.model;

import java.time.OffsetDateTime;
import java.util.List;

public record MenuAlternativeResult(long sourceStoreId, long sourceMenuId, int quantity,
        OffsetDateTime startAt, OffsetDateTime serviceEndAt, String timeZoneId,
        MenuAlternativeMode mode, List<ResolvedAlternativeItem> items) {
    public MenuAlternativeResult { items = List.copyOf(items); }
}
