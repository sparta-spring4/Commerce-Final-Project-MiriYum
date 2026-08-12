package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AudienceOpenApiContractTest {

    private static final Path SPECS = Path.of("..", "docs", "specs");
    private static final Set<String> NON_FEATURE_OPENAPI_FILES =
            Set.of("mvp1-common/openapi.yaml");
    private static final Set<String> APPROVED_UNEXPOSED_FEATURE_PATHS = Set.of();
    private static final String MENU_ALTERNATIVE_SEARCH_PATH =
            "/api/v1/stores/{storeId}/menus/{menuId}/alternatives/search";
    private static final String NOTIFICATION_HISTORY_PATH =
            "/api/v1/consumers/me/notifications";
    private static final Set<String> POST_MVP1_AUDIENCE_PATHS =
            Set.of(MENU_ALTERNATIVE_SEARCH_PATH, NOTIFICATION_HISTORY_PATH);
    private static final Set<String> LEGACY_PREFIXES = Set.of(
            "/api/v1/consumer-auth",
            "/api/v1/consumer-accounts",
            "/api/v1/store-operator-auth",
            "/api/v1/store-operator-accounts",
            "/api/v1/store-operator/",
            "/api/v1/reservations",
            "/api/v1/pickup-reservations");

    @Test
    void audienceEntrypointsMatchTheMvp1AggregateAndLaterStagePaths() throws IOException {
        Set<String> publicPaths = paths("public-openapi.yaml").keySet();
        Set<String> consumerPaths = paths("consumer-openapi.yaml").keySet();
        Set<String> operatorPaths = paths("store-operator-openapi.yaml").keySet();
        Set<String> aggregatePaths = paths("mvp1-openapi.yaml").keySet();

        assertThat(consumerPaths)
                .isNotEmpty()
                .allMatch(path -> path.startsWith("/api/v1/consumers/"));
        assertThat(operatorPaths)
                .isNotEmpty()
                .allMatch(path -> path.startsWith("/api/v1/store-operators/"));
        assertThat(intersection(publicPaths, consumerPaths)).isEmpty();
        assertThat(intersection(publicPaths, operatorPaths)).isEmpty();
        assertThat(intersection(consumerPaths, operatorPaths)).isEmpty();

        Set<String> allAudiencePaths = new HashSet<>(publicPaths);
        allAudiencePaths.addAll(consumerPaths);
        allAudiencePaths.addAll(operatorPaths);
        assertThat(intersection(aggregatePaths, POST_MVP1_AUDIENCE_PATHS)).isEmpty();
        Set<String> mvp1AndLaterStagePaths = new HashSet<>(aggregatePaths);
        mvp1AndLaterStagePaths.addAll(POST_MVP1_AUDIENCE_PATHS);
        assertThat(mvp1AndLaterStagePaths).isEqualTo(allAudiencePaths);
    }

    @Test
    void audienceEntrypointsExposeEveryFeaturePathUnlessExplicitlyExcluded() throws IOException {
        Set<String> featurePaths = new HashSet<>();
        for (String file : featureOpenApiFiles()) {
            featurePaths.addAll(paths(file).keySet());
        }

        Set<String> audiencePaths = new HashSet<>(paths("public-openapi.yaml").keySet());
        audiencePaths.addAll(paths("consumer-openapi.yaml").keySet());
        audiencePaths.addAll(paths("store-operator-openapi.yaml").keySet());

        assertThat(intersection(audiencePaths, APPROVED_UNEXPOSED_FEATURE_PATHS)).isEmpty();
        Set<String> exposedOrApprovedPaths = new HashSet<>(audiencePaths);
        exposedOrApprovedPaths.addAll(APPROVED_UNEXPOSED_FEATURE_PATHS);
        assertThat(exposedOrApprovedPaths).isEqualTo(featurePaths);
    }

    @Test
    void entrypointPathItemsAreSingleReferencesWithoutLegacyUrls() throws IOException {
        for (String file : Set.of(
                "public-openapi.yaml",
                "consumer-openapi.yaml",
                "store-operator-openapi.yaml",
                "mvp1-openapi.yaml")) {
            Map<String, Object> paths = paths(file);
            assertThat(paths).isNotEmpty();
            assertThat(paths.keySet())
                    .noneMatch(path -> LEGACY_PREFIXES.stream().anyMatch(path::startsWith));
            assertThat(paths.values())
                    .allSatisfy(item -> assertThat(map(item))
                            .containsOnlyKeys("$ref"));
        }
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static Set<String> featureOpenApiFiles() throws IOException {
        try (var entries = Files.list(SPECS)) {
            return entries
                    .filter(Files::isDirectory)
                    .map(directory -> directory.resolve("openapi.yaml"))
                    .filter(Files::isRegularFile)
                    .map(SPECS::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(file -> !NON_FEATURE_OPENAPI_FILES.contains(file))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    private static Map<String, Object> paths(String file) throws IOException {
        try (InputStream input = Files.newInputStream(SPECS.resolve(file))) {
            return map(map(new Yaml().load(input)).get("paths"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
