package com.miriyum.domain.search.interpreter;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TimeParser {

    private static final String KOREAN_RANGE_ENDPOINT =
            "(?:(?:오전|오후)\\s*)?[0-9]{1,2}\\s*시(?:\\s*[0-9]{1,2}\\s*분)?";
    private static final String TIME_RANGE_ENDPOINT =
            "(?:" + KOREAN_RANGE_ENDPOINT + "|[0-9]{1,2}:[0-9]{2})";
    private static final Pattern AM_PM_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(오전|오후)\\s*([0-9]{1,2})\\s*시"
                    + "(?:\\s*([0-9]{1,2})\\s*분"
                    + "|(?!\\s*\\S*[0-9]\\S*\\s*분))"
                    + "(?!\\s*[:.]\\S)(?![\\p{L}\\p{N}])");
    private static final Pattern TWENTY_FOUR_HOUR_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([0-9]{1,2}):([0-9]{2})"
                    + "(?![:.]\\S)(?![\\p{L}\\p{N}])");
    private static final Pattern MALFORMED_AM_PM_EXTENSION_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(오전|오후)\\s*[0-9]{1,2}\\s*시"
                    + "(?:\\s*(?![0-9]{1,2}\\s*분)\\S*[0-9]\\S*\\s*분"
                    + "|\\s*[:.]\\S+)"
                    + "(?![\\p{L}\\p{N}])");
    private static final Pattern MALFORMED_24_HOUR_EXTENSION_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])[0-9]{1,2}:[0-9]{2}[:.]\\S+"
                    + "(?![\\p{L}\\p{N}])");
    private static final Pattern UNSUPPORTED_TIME_RANGE_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])"
                    + TIME_RANGE_ENDPOINT
                    + "\\s*[-~～]\\s*"
                    + TIME_RANGE_ENDPOINT
                    + "(?:(?=\\s*(?:까지|쯤)(?![\\p{L}\\p{N}]))"
                    + "|(?![\\p{L}\\p{N}]))");
    private static final Pattern AMBIGUOUS_PERIOD_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(점심|저녁)(?:\\s*쯤)?(?![\\p{L}\\p{N}])");
    private static final Pattern APPROXIMATE_TIME_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:(?:오전|오후)\\s*[0-9]{1,2}\\s*시"
                    + "(?:\\s*[0-9]{1,2}\\s*분)?|[0-9]{1,2}:[0-9]{2})"
                    + "\\s*쯤(?![\\p{L}\\p{N}])");

    private TimeParser() {
    }

    static Result parse(String input) {
        List<TextSpan> spans = new ArrayList<>();
        List<TextSpan> recognizedSpans = new ArrayList<>();
        Integer ambiguousTimeStart = firstMatchStart(AMBIGUOUS_PERIOD_PATTERN, input);
        ambiguousTimeStart = earliest(
                ambiguousTimeStart, firstMatchStart(APPROXIMATE_TIME_PATTERN, input));
        ambiguousTimeStart = earliest(ambiguousTimeStart,
                firstMatchStart(MALFORMED_AM_PM_EXTENSION_PATTERN, input));
        ambiguousTimeStart = earliest(ambiguousTimeStart,
                firstMatchStart(MALFORMED_24_HOUR_EXTENSION_PATTERN, input));
        Matcher unsupportedRangeMatcher = UNSUPPORTED_TIME_RANGE_PATTERN.matcher(input);
        while (unsupportedRangeMatcher.find()) {
            TextSpan span = new TextSpan(
                    unsupportedRangeMatcher.start(), unsupportedRangeMatcher.end());
            recognizedSpans.add(span);
            ambiguousTimeStart = earliest(ambiguousTimeStart, span.startInclusive());
        }
        Matcher matcher = AM_PM_PATTERN.matcher(input);
        LinkedHashSet<LocalTime> values = new LinkedHashSet<>();
        while (matcher.find()) {
            TextSpan span = new TextSpan(matcher.start(), matcher.end());
            if (overlapsAny(span, recognizedSpans)) {
                continue;
            }
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
            if (overlapsAny(span, recognizedSpans)) {
                continue;
            }
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
        addMatches(MALFORMED_AM_PM_EXTENSION_PATTERN, input, recognizedSpans);
        addMatches(MALFORMED_24_HOUR_EXTENSION_PATTERN, input, recognizedSpans);
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

    private static boolean overlapsAny(TextSpan candidate, List<TextSpan> spans) {
        return spans.stream().anyMatch(candidate::overlaps);
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
