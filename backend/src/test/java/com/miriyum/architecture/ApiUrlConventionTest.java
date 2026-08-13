package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

class ApiUrlConventionTest {

    private static final Pattern FIXED_SEGMENT = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    private static final Set<String> COMMAND_SEGMENTS = Set.of(
            "call", "arrive", "check-in", "cancel", "search",
            "publication", "publication-cancellation", "retirement");
    private static final Set<String> POST_SINGLETON_EXCEPTIONS = Set.of("portone");
    private static final Set<String> SELF_OWNED_CONSUMER_COLLECTIONS = Set.of(
            "reservations", "pickup-reservations", "payments");

    @Test
    void controllerPackagesUseTheirCanonicalAudienceNamespace() {
        Set<ControllerRoute> routes = SpringMvcRouteInventory.routes();
        List<ControllerRoute> violations = routes.stream()
                .filter(route -> !usesCanonicalAudienceNamespace(route))
                .toList();

        assertThat(routes).isNotEmpty();
        assertThat(violations).as("controller package and audience namespace mismatches").isEmpty();
    }

    @Test
    void fixedSegmentsUseLowercaseKebabCase() {
        List<ApiRoute> violations = apiRoutes().stream()
                .filter(route -> segments(route.path()).stream()
                        .filter(segment -> !segment.startsWith("{"))
                        .anyMatch(segment -> !FIXED_SEGMENT.matcher(segment).matches()))
                .toList();

        assertThat(violations).as("non-kebab-case API routes").isEmpty();
    }

    @Test
    void consumerOwnedResourcesAreNestedUnderMe() {
        List<ApiRoute> violations = apiRoutes().stream()
                .filter(route -> SELF_OWNED_CONSUMER_COLLECTIONS.stream()
                        .anyMatch(resource -> route.path().startsWith(
                                "/api/v1/consumers/" + resource)))
                .toList();

        assertThat(violations).as("consumer-owned routes outside /consumers/me").isEmpty();
    }

    @Test
    void commandVerbsAreModeledAsPluralEventResources() {
        List<ApiRoute> commandViolations = apiRoutes().stream()
                .filter(route -> segments(route.path()).stream().anyMatch(COMMAND_SEGMENTS::contains))
                .toList();
        List<ApiRoute> singularPostTargets = apiRoutes().stream()
                .filter(route -> route.method() == RequestMethod.POST)
                .filter(route -> {
                    String target = lastFixedSegment(route.path());
                    return !target.endsWith("s") && !POST_SINGLETON_EXCEPTIONS.contains(target);
                })
                .toList();

        assertThat(commandViolations).as("verb-style command segments").isEmpty();
        assertThat(singularPostTargets).as("singular POST resource targets").isEmpty();
    }

    private static boolean usesCanonicalAudienceNamespace(ControllerRoute route) {
        String packageName = route.packageName();
        String path = route.route().path();
        if (packageName.startsWith("com.miriyum.domain.consumer.controller.")
                || packageName.contains(".controller.consumer")) {
            return path.startsWith("/api/v1/consumers/") || path.equals("/api/v1/consumers");
        }
        if (packageName.startsWith("com.miriyum.domain.storeoperator.controller.")
                || packageName.contains(".controller.storeoperator")) {
            return path.startsWith("/api/v1/store-operators/")
                    || path.equals("/api/v1/store-operators");
        }
        return packageName.contains(".controller.publicapi") && path.startsWith("/api/v1/");
    }

    private static Set<ApiRoute> apiRoutes() {
        return SpringMvcRouteInventory.routes().stream()
                .map(ControllerRoute::route)
                .filter(route -> route.path().startsWith("/api/v1/"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<String> segments(String path) {
        return Pattern.compile("/").splitAsStream(path)
                .filter(segment -> !segment.isBlank())
                .toList();
    }

    private static String lastFixedSegment(String path) {
        List<String> segments = segments(path);
        for (int index = segments.size() - 1; index >= 0; index--) {
            String segment = segments.get(index);
            if (!segment.startsWith("{")) {
                return segment;
            }
        }
        throw new IllegalArgumentException("Route contains no fixed segment: " + path);
    }
}
