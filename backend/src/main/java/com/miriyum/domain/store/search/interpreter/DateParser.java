package com.miriyum.domain.store.search.interpreter;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DateParser {

    private static final Pattern RELATIVE_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(오늘|내일|모레)(?![\\p{L}\\p{N}])");
    private static final Pattern ISO_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([0-9]{4})-([0-9]{2})-([0-9]{2})(?![\\p{L}\\p{N}])");
    private static final Pattern KOREAN_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([0-9]{4})년\\s*([0-9]{1,2})월\\s*([0-9]{1,2})일"
                    + "(?![\\p{L}\\p{N}])");
    private static final Pattern YEARLESS_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([0-9]{1,2})월\\s*([0-9]{1,2})일(?![\\p{L}\\p{N}])");

    private DateParser() {
    }

    static Result parse(String input, Clock clock, ZoneId zoneId) {
        Matcher matcher = RELATIVE_PATTERN.matcher(input);
        LinkedHashSet<LocalDate> values = new LinkedHashSet<>();
        List<TextSpan> spans = new ArrayList<>();
        boolean hasInvalidDate = false;
        LocalDate today = LocalDate.now(clock.withZone(zoneId));
        while (matcher.find()) {
            LocalDate value = switch (matcher.group(1)) {
                case "오늘" -> today;
                case "내일" -> today.plusDays(1);
                case "모레" -> today.plusDays(2);
                default -> throw new IllegalStateException("unsupported relative date");
            };
            values.add(value);
            spans.add(new TextSpan(matcher.start(), matcher.end()));
        }

        Matcher isoMatcher = ISO_PATTERN.matcher(input);
        while (isoMatcher.find()) {
            try {
                values.add(LocalDate.of(
                        Integer.parseInt(isoMatcher.group(1)),
                        Integer.parseInt(isoMatcher.group(2)),
                        Integer.parseInt(isoMatcher.group(3))));
                spans.add(new TextSpan(isoMatcher.start(), isoMatcher.end()));
            } catch (DateTimeException exception) {
                hasInvalidDate = true;
            }
        }

        Matcher koreanMatcher = KOREAN_PATTERN.matcher(input);
        while (koreanMatcher.find()) {
            try {
                values.add(LocalDate.of(
                        Integer.parseInt(koreanMatcher.group(1)),
                        Integer.parseInt(koreanMatcher.group(2)),
                        Integer.parseInt(koreanMatcher.group(3))));
                spans.add(new TextSpan(koreanMatcher.start(), koreanMatcher.end()));
            } catch (DateTimeException exception) {
                hasInvalidDate = true;
            }
        }

        Matcher yearlessMatcher = YEARLESS_PATTERN.matcher(input);
        while (yearlessMatcher.find()) {
            TextSpan span = new TextSpan(yearlessMatcher.start(), yearlessMatcher.end());
            if (spans.stream().noneMatch(span::overlaps)) {
                hasInvalidDate = true;
            }
        }
        if (values.size() > 1) {
            return new Result(
                    null,
                    List.of(),
                    List.of(new InterpretationWarning(
                            WarningCode.CONFLICTING_DATE,
                            WarningField.DATE)));
        }
        LocalDate value = values.isEmpty() ? null : values.getFirst();
        List<InterpretationWarning> warnings = hasInvalidDate
                ? List.of(new InterpretationWarning(
                        WarningCode.AMBIGUOUS_DATE,
                        WarningField.DATE))
                : List.of();
        return new Result(value, spans, warnings);
    }

    record Result(
            LocalDate value,
            List<TextSpan> acceptedSpans,
            List<InterpretationWarning> warnings) {

        Result {
            acceptedSpans = List.copyOf(acceptedSpans);
            warnings = List.copyOf(warnings);
        }
    }
}
