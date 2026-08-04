package com.miriyum.domain.store.search.interpreter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PartySizeParser {

    private static final Pattern PARTY_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(-?[0-9][0-9,]*)\\s*명(?![\\p{L}\\p{N}])");

    private PartySizeParser() {
    }

    static Result parse(String input) {
        Matcher matcher = PARTY_PATTERN.matcher(input);
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        List<TextSpan> spans = new ArrayList<>();
        boolean hasInvalidValue = false;
        boolean hasOutOfRangeNumber = false;
        while (matcher.find()) {
            try {
                long parsed = Long.parseLong(matcher.group(1).replace(",", ""));
                if (parsed < 1) {
                    hasInvalidValue = true;
                    continue;
                }
                values.add(Math.toIntExact(parsed));
                spans.add(new TextSpan(matcher.start(), matcher.end()));
            } catch (ArithmeticException | NumberFormatException exception) {
                hasOutOfRangeNumber = true;
            }
        }
        if (values.size() > 1) {
            return new Result(
                    null,
                    List.of(),
                    List.of(new InterpretationWarning(
                            WarningCode.CONFLICTING_PARTY_SIZE,
                            WarningField.PARTY_SIZE)));
        }
        Integer partySize = values.isEmpty() ? null : values.getFirst();
        List<InterpretationWarning> warnings = new ArrayList<>();
        if (hasInvalidValue) {
            warnings.add(new InterpretationWarning(
                    WarningCode.INVALID_PARTY_SIZE,
                    WarningField.PARTY_SIZE));
        }
        if (hasOutOfRangeNumber) {
            warnings.add(new InterpretationWarning(
                    WarningCode.OUT_OF_RANGE_NUMBER,
                    WarningField.PARTY_SIZE));
        }
        return new Result(partySize, spans, warnings);
    }

    record Result(
            Integer value,
            List<TextSpan> acceptedSpans,
            List<InterpretationWarning> warnings) {

        Result {
            acceptedSpans = List.copyOf(acceptedSpans);
            warnings = List.copyOf(warnings);
        }
    }
}
