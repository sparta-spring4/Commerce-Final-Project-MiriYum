package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AudienceOpenApiContractTest {

    private static final Path SPECS = Path.of("..", "docs", "specs");
    private static final String MENU_ALTERNATIVE_SEARCH_PATH =
            "/api/v1/stores/{storeId}/menus/{menuId}/alternatives/search";
    private static final Set<String> LEGACY_PREFIXES = Set.of(
            "/api/v1/consumer-auth",
            "/api/v1/consumer-accounts",
            "/api/v1/store-operator-auth",
            "/api/v1/store-operator-accounts",
            "/api/v1/store-operator/",
            "/api/v1/reservations",
            "/api/v1/pickup-reservations");

    @Test
    void audienceEntrypointsPartitionTheAggregatePaths() throws IOException {
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
        assertThat(allAudiencePaths).isEqualTo(aggregatePaths);
    }

    @Test
    void publicMenuAlternativeSearchIsExposedThroughBothEntrypoints() throws IOException {
        assertThat(paths("public-openapi.yaml")).containsKey(MENU_ALTERNATIVE_SEARCH_PATH);
        assertThat(paths("mvp1-openapi.yaml")).containsKey(MENU_ALTERNATIVE_SEARCH_PATH);
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
