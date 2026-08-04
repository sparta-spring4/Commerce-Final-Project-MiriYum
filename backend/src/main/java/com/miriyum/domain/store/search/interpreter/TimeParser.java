package com.miriyum.domain.store.search.interpreter;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TimeParser {

    private static final Pattern AM_PM_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(오전|오후)\\s*([0-9]{1,2})\\s*시"
                    + "(?:\\s*([0-9]{1,2})\\s*분)?(?![\\p{L}\\p{N}])");
    private static final Pattern TWENTY_FOUR_HOUR_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([0-9]{1,2}):([0-9]{2})(?![\\p{L}\\p{N}])");
    private static final Pattern AMBIGUOUS_PERIOD_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(점심|저녁)(?:\\s*쯤)?(?![\\p{L}\\p{N}])");
    private static final Pattern APPROXIMATE_TIME_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:(?:오전|오후)\\s*[0-9]{1,2}\\s*시"
                    + "(?:\\s*[0-9]{1,2}\\s*분)?|[0-9]{1,2}:[0-9]{2})"
                    + "\\s*쯤(?![\\p{L}\\p{N}])");

    private TimeParser() {
    }

    static Result parse(String input) {
        Integer ambiguousTimeStart = firstMatchStart(AMBIGUOUS_PERIOD_PATTERN, input);
        ambiguousTimeStart = earliest(
                ambiguousTimeStart, firstMatchStart(APPROXIMATE_TIME_PATTERN, input));
        Matcher matcher = AM_PM_PATTERN.matcher(input);
        LinkedHashSet<LocalTime> values = new LinkedHashSet<>();
        List<TextSpan> spans = new ArrayList<>();
        List<TextSpan> recognizedSpans = new ArrayList<>();
        while (matcher.find()) {
            TextSpan span = new TextSpan(matcher.start(), matcher.end());
            recognizedSpans.add(span);
            try {
                int hour = Integer.parseInt(matcher.group(2));
                int minute = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
                if (hour < 1 || hour > 12) {
                    throw new DateTimeException("12-hour clock hour is out of range");
                }
                int normalizedHour = hour % 12;
                if ("오후".equals(matcher.group(1))) {
                    normalizedHour += 12;
                }
                values.add(LocalTime.of(normalizedHour, minute));
                spans.add(span);
            } catch (DateTimeException exception) {
                ambiguousTimeStart = earliest(
                        ambiguousTimeStart, span.startInclusive());
            }
        }
        Matcher twentyFourHourMatcher = TWENTY_FOUR_HOUR_PATTERN.matcher(input);
        while (twentyFourHourMatcher.find()) {
            TextSpan span = new TextSpan(
                    twentyFourHourMatcher.start(),
                    twentyFourHourMatcher.end());
            recognizedSpans.add(span);
            try {
                int hour = Integer.parseInt(twentyFourHourMatcher.group(1));
                int minute = Integer.parseInt(twentyFourHourMatcher.group(2));
                values.add(LocalTime.of(hour, minute));
                spans.add(span);
            } catch (DateTimeException exception) {
                ambiguousTimeStart = earliest(
                        ambiguousTimeStart, span.startInclusive());
            }
        }
        addMatches(AMBIGUOUS_PERIOD_PATTERN, input, recognizedSpans);
        addMatches(APPROXIMATE_TIME_PATTERN, input, recognizedSpans);
        if (values.size() > 1) {
            return new Result(
                    null,
                    List.of(),
                    recognizedSpans,
                    List.of(new LocatedWarning(
                            new InterpretationWarning(
                                    WarningCode.CONFLICTING_TIME,
                                    WarningField.TIME),
                            earliestStart(recognizedSpans))));
        }
        LocalTime value = values.isEmpty() ? null : values.getFirst();
        List<LocatedWarning> warnings = ambiguousTimeStart != null
                ? List.of(new LocatedWarning(
                        new InterpretationWarning(
                                WarningCode.AMBIGUOUS_TIME,
                                WarningField.TIME),
                        ambiguousTimeStart))
                : List.of();
        return new Result(value, spans, recognizedSpans, warnings);
    }

    private static Integer firstMatchStart(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.start() : null;
    }

    private static Integer earliest(Integer current, Integer candidate) {
        if (current == null) {
            return candidate;
        }
        return candidate == null ? current : Math.min(current, candidate);
    }

    private static int earliestStart(List<TextSpan> spans) {
        return spans.stream().mapToInt(TextSpan::startInclusive).min().orElse(0);
    }

    private static void addMatches(Pattern pattern, String input, List<TextSpan> spans) {
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            TextSpan span = new TextSpan(matcher.start(), matcher.end());
            if (spans.stream().noneMatch(span::equals)) {
                spans.add(span);
            }
        }
    }

    record Result(
            LocalTime value,
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
