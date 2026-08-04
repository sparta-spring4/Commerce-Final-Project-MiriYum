package com.miriyum.domain.store.search.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PriceParser {

    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([0-9][0-9,]*)\\s*(천|만)?\\s*원?\\s*(?:~|～|-)\\s*"
                    + "([0-9][0-9,]*)\\s*(천|만)?\\s*원(?![\\p{L}\\p{N}])");
    private static final Pattern BOUND_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([0-9][0-9,]*)\\s*(천|만)?\\s*원\\s*(이상|이하|미만|초과)(?![\\p{L}\\p{N}])");
    private static final Pattern EXACT_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([0-9][0-9,]*)\\s*(천|만)?\\s*원(?![\\p{L}\\p{N}])");
    private static final Pattern AMBIGUOUS_BAND_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])[0-9][0-9,]*\\s*만원대(?![\\p{L}\\p{N}])");

    private PriceParser() {
    }

    static Result parse(String input) {
        Matcher ambiguousBandMatcher = AMBIGUOUS_BAND_PATTERN.matcher(input);
        boolean hasAmbiguousBand = false;
        Long minimum = null;
        Long maximum = null;
        List<TextSpan> spans = new ArrayList<>();
        List<TextSpan> recognizedSpans = new ArrayList<>();
        boolean hasOutOfRangeNumber = false;

        while (ambiguousBandMatcher.find()) {
            hasAmbiguousBand = true;
            recognizedSpans.add(new TextSpan(
                    ambiguousBandMatcher.start(),
                    ambiguousBandMatcher.end()));
        }

        Matcher rangeMatcher = RANGE_PATTERN.matcher(input);
        while (rangeMatcher.find()) {
            TextSpan span = new TextSpan(rangeMatcher.start(), rangeMatcher.end());
            recognizedSpans.add(span);
            String leftUnit = rangeMatcher.group(2) == null
                    ? rangeMatcher.group(4)
                    : rangeMatcher.group(2);
            String rightUnit = rangeMatcher.group(4) == null
                    ? rangeMatcher.group(2)
                    : rangeMatcher.group(4);
            try {
                long left = toWon(rangeMatcher.group(1), leftUnit);
                long right = toWon(rangeMatcher.group(3), rightUnit);
                minimum = maximum(minimum, left);
                maximum = minimum(maximum, right);
                spans.add(span);
            } catch (ArithmeticException | NumberFormatException exception) {
                hasOutOfRangeNumber = true;
            }
        }

        Matcher matcher = BOUND_PATTERN.matcher(input);
        while (matcher.find()) {
            TextSpan span = new TextSpan(matcher.start(), matcher.end());
            if (overlapsAny(span, recognizedSpans)) {
                continue;
            }
            recognizedSpans.add(span);
            try {
                long amount = toWon(matcher.group(1), matcher.group(2));
                String operator = matcher.group(3);
                switch (operator) {
                    case "이상" -> minimum = maximum(minimum, amount);
                    case "초과" -> minimum = maximum(minimum, Math.addExact(amount, 1));
                    case "이하" -> maximum = minimum(maximum, amount);
                    case "미만" -> maximum = minimum(maximum, Math.subtractExact(amount, 1));
                    default -> throw new IllegalStateException("unsupported price operator");
                }
                spans.add(span);
            } catch (ArithmeticException | NumberFormatException exception) {
                hasOutOfRangeNumber = true;
            }
        }

        Matcher exactMatcher = EXACT_PATTERN.matcher(input);
        while (exactMatcher.find()) {
            TextSpan span = new TextSpan(exactMatcher.start(), exactMatcher.end());
            if (overlapsAny(span, recognizedSpans)) {
                continue;
            }
            recognizedSpans.add(span);
            try {
                long amount = toWon(exactMatcher.group(1), exactMatcher.group(2));
                minimum = maximum(minimum, amount);
                maximum = minimum(maximum, amount);
                spans.add(span);
            } catch (ArithmeticException | NumberFormatException exception) {
                hasOutOfRangeNumber = true;
            }
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            return new Result(
                    null,
                    List.of(),
                    recognizedSpans,
                    List.of(new InterpretationWarning(
                            WarningCode.CONFLICTING_PRICE,
                            WarningField.PRICE)));
        }
        if ((minimum != null && minimum < 0) || (maximum != null && maximum < 0)) {
            return new Result(
                    null,
                    List.of(),
                    recognizedSpans,
                    List.of(new InterpretationWarning(
                            WarningCode.OUT_OF_RANGE_NUMBER,
                            WarningField.PRICE)));
        }
        PriceRange range = minimum == null && maximum == null
                ? null
                : new PriceRange(minimum, maximum);
        List<InterpretationWarning> warnings = new ArrayList<>();
        if (hasAmbiguousBand) {
            warnings.add(new InterpretationWarning(
                    WarningCode.AMBIGUOUS_PRICE,
                    WarningField.PRICE));
        }
        if (hasOutOfRangeNumber) {
            warnings.add(new InterpretationWarning(
                    WarningCode.OUT_OF_RANGE_NUMBER,
                    WarningField.PRICE));
        }
        return new Result(range, spans, recognizedSpans, warnings);
    }

    private static long toWon(String rawNumber, String unit) {
        long number = Long.parseLong(rawNumber.replace(",", ""));
        long multiplier = switch (unit == null ? "" : unit) {
            case "천" -> 1_000L;
            case "만" -> 10_000L;
            default -> 1L;
        };
        return Math.multiplyExact(number, multiplier);
    }

    private static Long maximum(Long current, long candidate) {
        return current == null ? candidate : Math.max(current, candidate);
    }

    private static Long minimum(Long current, long candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static boolean overlapsAny(TextSpan candidate, List<TextSpan> spans) {
        return spans.stream().anyMatch(candidate::overlaps);
    }

    record Result(
            PriceRange value,
            List<TextSpan> acceptedSpans,
            List<TextSpan> recognizedSpans,
            List<InterpretationWarning> warnings) {

        Result {
            acceptedSpans = List.copyOf(acceptedSpans);
            recognizedSpans = List.copyOf(recognizedSpans);
            warnings = List.copyOf(warnings);
        }
    }
}
