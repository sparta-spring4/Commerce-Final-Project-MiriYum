package com.miriyum.architecture;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class ApiUrlConvention {

    private static final Pattern FIXED_SEGMENT =
            Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    private static final Set<String> SINGLETON_SEGMENTS = Set.of(
            "auth", "me", "current", "contact", "visibility", "selling-status",
            "end-at", "menu-hold-availability", "pickup-availability",
            "deactivation-impact", "initial-password", "portone", "kakao",
            "authority", "suspension", "unread-count");
    private static final Set<String> LEGACY_COMMAND_SEGMENTS = Set.of(
            "publication", "publication-cancellation", "retirement", "cancellation",
            "call", "arrive", "check-in", "cancel");

    private ApiUrlConvention() {
    }

    static List<String> violations(Set<ApiRoute> routes) {
        List<String> violations = new ArrayList<>();
        for (ApiRoute route : routes) {
            String violation = violation(route.path());
            if (violation != null) {
                violations.add(route.method() + " " + route.path() + ": " + violation);
            }
        }
        return List.copyOf(violations);
    }

    private static String violation(String path) {
        List<String> segments = segments(path);
        if (segments.size() < 3
                || !segments.get(0).equals("api")
                || !segments.get(1).equals("v1")) {
            return "route must start with /api/v1";
        }
        if (hasLegacyShape(path, segments)) {
            return "legacy URL shape is forbidden";
        }
        String audienceViolation = audienceViolation(segments);
        if (audienceViolation != null) {
            return audienceViolation;
        }
        for (int index = 2; index < segments.size(); index++) {
            String segment = segments.get(index);
            if (segment.startsWith("{")) {
                continue;
            }
            if (!FIXED_SEGMENT.matcher(segment).matches()) {
                return "fixed segments must use lowercase kebab-case";
            }
            if (!segment.endsWith("s") && !SINGLETON_SEGMENTS.contains(segment)) {
                return "resource segments must be plural unless explicitly approved as singleton";
            }
        }
        return null;
    }

    private static boolean hasLegacyShape(String path, List<String> segments) {
        return segments.stream().anyMatch(LEGACY_COMMAND_SEGMENTS::contains)
                || path.contains("/waiting-close-jobs/")
                || path.endsWith("/waiting-close-jobs")
                || path.contains("/waiting-settings/disable-impact")
                || path.contains("/alternatives/search");
    }

    private static String audienceViolation(List<String> segments) {
        String audience = segments.get(2);
        boolean accountAudience = audience.equals("consumers")
                || audience.equals("store-operators")
                || audience.equals("platform-operators");
        for (int index = 3; index < segments.size(); index++) {
            boolean accountScope = segments.get(index).equals("me")
                    || segments.get(index).equals("auth");
            if (accountScope && !(accountAudience && index == 3)) {
                return "/me and /auth are allowed only in their canonical audience position";
            }
        }
        if (audience.equals("consumers")) {
            if (segments.size() < 4 || (!Set.of("me", "auth").contains(segments.get(3))
                    && !isMemberSupportSegment(segments.get(3)))) {
                return "consumer-owned routes require /consumers/me or /consumers/auth scope";
            }
        } else if (audience.equals("store-operators")) {
            if (segments.size() < 4
                    || (!Set.of("me", "auth", "stores").contains(segments.get(3))
                    && !isMemberSupportSegment(segments.get(3)))) {
                return "store-operator routes require /me, /auth, or /stores scope";
            }
        }
        return null;
    }

    private static boolean isMemberSupportSegment(String segment) {
        return segment.startsWith("account-recovery-") || segment.equals("account-sanction-appeals");
    }

    private static List<String> segments(String path) {
        return Pattern.compile("/").splitAsStream(path)
                .filter(segment -> !segment.isBlank())
                .toList();
    }
}
