package com.miriyum.domain.store.closure.dto;

import com.miriyum.domain.store.closure.entity.RegularClosureEntry;
import com.miriyum.domain.store.closure.entity.RegularClosureVersion;
import com.miriyum.domain.store.closure.model.RegularClosureRuleType;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record RegularClosureResponse(
        long version,
        ScheduleVersionStatus status,
        String timeZoneId,
        Instant effectiveAt,
        String changeReason,
        List<DayOfWeek> weeklyDays,
        List<LocalDate> dates
) {
    public static RegularClosureResponse from(RegularClosureVersion version) {
        List<RegularClosureEntry> entries = version.getEntries();
        return new RegularClosureResponse(
                version.getVersionNumber(), version.getStatus(), version.getTimeZoneId(),
                version.getEffectiveAt(), version.getChangeReason(),
                entries.stream().filter(e -> e.getRuleType() == RegularClosureRuleType.WEEKLY)
                        .map(RegularClosureEntry::getDayOfWeek).toList(),
                entries.stream().filter(e -> e.getRuleType() == RegularClosureRuleType.DATE)
                        .map(RegularClosureEntry::getClosureDate).toList());
    }
}
