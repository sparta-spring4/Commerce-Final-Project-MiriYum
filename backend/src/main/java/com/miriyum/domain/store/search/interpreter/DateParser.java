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
        List<TextSpan> recognizedSpans = new ArrayList<>();
        Integer invalidDateStart = null;
        LocalDate today = LocalDate.now(clock.withZone(zoneId));
        while (matcher.find()) {
            TextSpan span = new TextSpan(matcher.start(), matcher.end());
            recognizedSpans.add(span);
            LocalDate value = switch (matcher.group(1)) {
                case "오늘" -> today;
                case "내일" -> today.plusDays(1);
                case "모레" -> today.plusDays(2);
                default -> throw new IllegalStateException("unsupported relative date");
            };
            values.add(value);
            spans.add(span);
        }

        Matcher isoMatcher = ISO_PATTERN.matcher(input);
        while (isoMatcher.find()) {
            TextSpan span = new TextSpan(isoMatcher.start(), isoMatcher.end());
            recognizedSpans.add(span);
            try {
                values.add(LocalDate.of(
                        Integer.parseInt(isoMatcher.group(1)),
                        Integer.parseInt(isoMatcher.group(2)),
                        Integer.parseInt(isoMatcher.group(3))));
                spans.add(span);
            } catch (DateTimeException exception) {
                invalidDateStart = earliest(invalidDateStart, span.startInclusive());
            }
        }

        Matcher koreanMatcher = KOREAN_PATTERN.matcher(input);
        while (koreanMatcher.find()) {
            TextSpan span = new TextSpan(koreanMatcher.start(), koreanMatcher.end());
            recognizedSpans.add(span);
            try {
                values.add(LocalDate.of(
                        Integer.parseInt(koreanMatcher.group(1)),
                        Integer.parseInt(koreanMatcher.group(2)),
                        Integer.parseInt(koreanMatcher.group(3))));
                spans.add(span);
            } catch (DateTimeException exception) {
                invalidDateStart = earliest(invalidDateStart, span.startInclusive());
            }
        }

        Matcher yearlessMatcher = YEARLESS_PATTERN.matcher(input);
        while (yearlessMatcher.find()) {
            TextSpan span = new TextSpan(yearlessMatcher.start(), yearlessMatcher.end());
            if (recognizedSpans.stream().noneMatch(span::overlaps)) {
                recognizedSpans.add(span);
            }
            if (spans.stream().noneMatch(span::overlaps)) {
                invalidDateStart = earliest(invalidDateStart, span.startInclusive());
            }
        }
        if (values.size() > 1) {
            return new Result(
                    null,
                    List.of(),
                    recognizedSpans,
                    List.of(new LocatedWarning(
                            new InterpretationWarning(
                                    WarningCode.CONFLICTING_DATE,
                                    WarningField.DATE),
                            earliestStart(recognizedSpans))));
        }
        LocalDate value = values.isEmpty() ? null : values.getFirst();
        List<LocatedWarning> warnings = invalidDateStart != null
                ? List.of(new LocatedWarning(
                        new InterpretationWarning(
                                WarningCode.AMBIGUOUS_DATE,
                                WarningField.DATE),
                        invalidDateStart))
                : List.of();
        return new Result(value, spans, recognizedSpans, warnings);
    }

    private static Integer earliest(Integer current, int candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static int earliestStart(List<TextSpan> spans) {
        return spans.stream().mapToInt(TextSpan::startInclusive).min().orElse(0);
    }

    record Result(
            LocalDate value,
            List<TextSpan> acceptedSpans,
            List<TextSpan> recognizedSpans,
            List<LocatedWarning> warnings) {

        Result {
            acceptedSpans = List.copyOf(acceptedSpans);
            recognizedSpans = List.copyOf(recognizedSpans);
            warnings = List.copyOf(warnings);
        }
    }
}
