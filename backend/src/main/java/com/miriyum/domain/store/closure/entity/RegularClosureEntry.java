package com.miriyum.domain.store.closure.entity;

import com.miriyum.domain.store.closure.model.RegularClosureRuleType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.DayOfWeek;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegularClosureEntry {

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 10)
    private RegularClosureRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 10)
    private DayOfWeek dayOfWeek;

    @Column(name = "closure_date")
    private LocalDate closureDate;

    private RegularClosureEntry(
            RegularClosureRuleType ruleType,
            DayOfWeek dayOfWeek,
            LocalDate closureDate
    ) {
        this.ruleType = ruleType;
        this.dayOfWeek = dayOfWeek;
        this.closureDate = closureDate;
    }

    public static RegularClosureEntry weekly(DayOfWeek dayOfWeek) {
        if (dayOfWeek == null) {
            throw new IllegalArgumentException("weekly closure day is required");
        }
        return new RegularClosureEntry(
                RegularClosureRuleType.WEEKLY, dayOfWeek, null);
    }

    public static RegularClosureEntry date(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("closure date is required");
        }
        return new RegularClosureEntry(RegularClosureRuleType.DATE, null, date);
    }

    public boolean matches(LocalDate localDate) {
        return ruleType == RegularClosureRuleType.WEEKLY
                ? localDate.getDayOfWeek() == dayOfWeek
                : localDate.equals(closureDate);
    }

    public String ruleKey() {
        return ruleType == RegularClosureRuleType.WEEKLY
                ? "WEEKLY:" + dayOfWeek
                : "DATE:" + closureDate;
    }
}
