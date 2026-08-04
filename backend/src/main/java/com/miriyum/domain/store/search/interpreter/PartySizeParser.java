package com.miriyum.domain.store.search.interpreter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PartySizeParser {

    private static final Pattern PARTY_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([-+]?[0-9][0-9,.]*)\\s*명(?![\\p{L}\\p{N}])");
    private static final Pattern CANONICAL_INTEGER = Pattern.compile("-?[0-9]+");

    private PartySizeParser() {
    }

    static Result parse(String input) {
        Matcher matcher = PARTY_PATTERN.matcher(input);
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        List<TextSpan> spans = new ArrayList<>();
        List<TextSpan> recognizedSpans = new ArrayList<>();
        Integer invalidValueStart = null;
        Integer outOfRangeNumberStart = null;
        while (matcher.find()) {
            TextSpan span = new TextSpan(matcher.start(), matcher.end());
            recognizedSpans.add(span);
            if (!CANONICAL_INTEGER.matcher(matcher.group(1)).matches()) {
                invalidValueStart = earliest(invalidValueStart, span.startInclusive());
                continue;
            }
            try {
                long parsed = Long.parseLong(matcher.group(1));
                if (parsed < 1) {
                    invalidValueStart = earliest(invalidValueStart, span.startInclusive());
                    continue;
                }
                values.add(Math.toIntExact(parsed));
                spans.add(span);
            } catch (ArithmeticException | NumberFormatException exception) {
                outOfRangeNumberStart = earliest(
                        outOfRangeNumberStart, span.startInclusive());
            }
        }
        if (values.size() > 1) {
            return new Result(
                    null,
                    List.of(),
                    recognizedSpans,
                    List.of(new LocatedWarning(
                            new InterpretationWarning(
                                    WarningCode.CONFLICTING_PARTY_SIZE,
                                    WarningField.PARTY_SIZE),
                            earliestStart(recognizedSpans))));
        }
        Integer partySize = values.isEmpty() ? null : values.getFirst();
        List<LocatedWarning> warnings = new ArrayList<>();
        if (invalidValueStart != null) {
            warnings.add(new LocatedWarning(
                    new InterpretationWarning(
                            WarningCode.INVALID_PARTY_SIZE,
                            WarningField.PARTY_SIZE),
                    invalidValueStart));
        }
        if (outOfRangeNumberStart != null) {
            warnings.add(new LocatedWarning(
                    new InterpretationWarning(
                            WarningCode.OUT_OF_RANGE_NUMBER,
                            WarningField.PARTY_SIZE),
                    outOfRangeNumberStart));
        }
        return new Result(partySize, spans, recognizedSpans, warnings);
    }

    private static Integer earliest(Integer current, int candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static int earliestStart(List<TextSpan> spans) {
        return spans.stream().mapToInt(TextSpan::startInclusive).min().orElse(0);
    }

    record Result(
            Integer value,
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
