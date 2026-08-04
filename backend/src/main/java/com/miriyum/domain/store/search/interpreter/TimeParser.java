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
        boolean hasAmbiguousPeriod = AMBIGUOUS_PERIOD_PATTERN.matcher(input).find();
        boolean hasApproximateTime = APPROXIMATE_TIME_PATTERN.matcher(input).find();
        boolean hasInvalidTime = false;
        Matcher matcher = AM_PM_PATTERN.matcher(input);
        LinkedHashSet<LocalTime> values = new LinkedHashSet<>();
        List<TextSpan> spans = new ArrayList<>();
        while (matcher.find()) {
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
                spans.add(new TextSpan(matcher.start(), matcher.end()));
            } catch (DateTimeException exception) {
                hasInvalidTime = true;
            }
        }
        Matcher twentyFourHourMatcher = TWENTY_FOUR_HOUR_PATTERN.matcher(input);
        while (twentyFourHourMatcher.find()) {
            try {
                int hour = Integer.parseInt(twentyFourHourMatcher.group(1));
                int minute = Integer.parseInt(twentyFourHourMatcher.group(2));
                values.add(LocalTime.of(hour, minute));
                spans.add(new TextSpan(
                        twentyFourHourMatcher.start(),
                        twentyFourHourMatcher.end()));
            } catch (DateTimeException exception) {
                hasInvalidTime = true;
            }
        }
        if (values.size() > 1) {
            return new Result(
                    null,
                    List.of(),
                    List.of(new InterpretationWarning(
                            WarningCode.CONFLICTING_TIME,
                            WarningField.TIME)));
        }
        LocalTime value = values.isEmpty() ? null : values.getFirst();
        List<InterpretationWarning> warnings = hasAmbiguousPeriod
                        || hasApproximateTime
                        || hasInvalidTime
                ? List.of(new InterpretationWarning(
                        WarningCode.AMBIGUOUS_TIME,
                        WarningField.TIME))
                : List.of();
        return new Result(value, spans, warnings);
    }

    record Result(
            LocalTime value,
            List<TextSpan> acceptedSpans,
            List<InterpretationWarning> warnings) {

        Result {
            acceptedSpans = List.copyOf(acceptedSpans);
            warnings = List.copyOf(warnings);
        }
    }
}
