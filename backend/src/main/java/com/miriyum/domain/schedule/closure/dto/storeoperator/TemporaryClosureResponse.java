package com.miriyum.domain.schedule.closure.dto.storeoperator;

import com.miriyum.domain.schedule.closure.entity.TemporaryClosure;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureReason;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureStatus;
import java.time.Instant;

public record TemporaryClosureResponse(
        long closureId, long storeId, Instant startAt, Instant endAt,
        String timeZoneId, TemporaryClosureReason reason, String publicMessage,
        TemporaryClosureStatus status, long changeVersion
) {
    public static TemporaryClosureResponse from(TemporaryClosure closure, Instant now) {
        return new TemporaryClosureResponse(closure.getId(), closure.getStoreId(), closure.getStartAt(),
                closure.getEndAt(), closure.getTimeZoneId(), closure.getReason(), closure.getPublicMessage(),
                closure.statusAt(now), closure.getChangeVersion());
    }
}
