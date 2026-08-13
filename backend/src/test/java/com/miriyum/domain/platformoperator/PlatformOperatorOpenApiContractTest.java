package com.miriyum.domain.platformoperator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PlatformOperatorOpenApiContractTest {
    private static final Path SPECS = Path.of("..", "docs", "specs");
    private static final Set<String> EXPECTED_PATHS = Set.of(
            "/api/v1/platform-operators/auth/sessions",
            "/api/v1/platform-operators/auth/token-refreshes",
            "/api/v1/platform-operators/auth/csrf-tokens/current",
            "/api/v1/platform-operators/auth/sessions/current",
            "/api/v1/platform-operators/auth/initial-password");

    @Test
    void platformAudienceHasOnlyTheFiveApprovedOperationsAndNoSignup() throws Exception {
        Map<String, Object> feature = document("platform-operator-auth/openapi.yaml");
        Map<String, Object> paths = map(feature.get("paths"));
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_PATHS);
        assertThat(paths).doesNotContainKey("/api/v1/platform-operators/auth/accounts");
        assertThat(map(map(map(feature.get("components")).get("schemas"))
                .get("PlatformOperatorTokenData")))
                .satisfies(schema -> assertThat(list(schema.get("required")))
                        .containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn",
                                "passwordChangeRequired", "idleExpiresAt", "absoluteExpiresAt"));
        Map<String, Object> tokenData = map(map(map(feature.get("components")).get("schemas"))
                .get("PlatformOperatorTokenData"));
        Map<String, Object> expiresIn = map(map(tokenData.get("properties")).get("expiresIn"));
        assertThat(expiresIn).containsEntry("const", 900);
    }

    @Test
    void audienceEntrypointUsesResolvingSingleRefsAndMvpAggregateExcludesIt() throws Exception {
        Map<String, Object> audiencePaths = map(document("platform-operator-openapi.yaml").get("paths"));
        assertThat(audiencePaths.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_PATHS);
        assertThat(audiencePaths.values()).allSatisfy(value -> assertThat(map(value)).containsOnlyKeys("$ref"));
        assertThat(map(document("mvp1-openapi.yaml").get("paths")).keySet()).doesNotContainAnyElementsOf(EXPECTED_PATHS);
        for (var entry : audiencePaths.entrySet()) {
            String ref = (String) map(entry.getValue()).get("$ref");
            assertThat(ref).startsWith("./platform-operator-auth/openapi.yaml#/paths/");
            assertThat(map(document("platform-operator-auth/openapi.yaml").get("paths"))).containsKey(entry.getKey());
        }
    }

    private static Map<String, Object> document(String file) throws Exception {
        try (InputStream input = Files.newInputStream(SPECS.resolve(file))) {
            return map(new Yaml().load(input));
        }
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked") private static java.util.List<Object> list(Object value) {
        return (java.util.List<Object>) value;
    }
}
