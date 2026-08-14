package com.miriyum.domain.platformoperator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import com.miriyum.domain.platformoperator.controller.auth.PlatformOperatorAuthController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PlatformOperatorOpenApiContractTest {
    private static final Path SPECS = Path.of("..", "docs", "specs");
    private static final Set<String> AUTH_PATHS = Set.of(
            "/api/v1/platform-operators/auth/sessions",
            "/api/v1/platform-operators/auth/token-refreshes",
            "/api/v1/platform-operators/auth/csrf-tokens/current",
            "/api/v1/platform-operators/auth/sessions/current",
            "/api/v1/platform-operators/auth/initial-password");
    private static final String REAUTHENTICATION_PATH =
            "/api/v1/platform-operators/reauthentication-approvals";
    private static final Set<String> EXPECTED_AUDIENCE_PATHS = java.util.stream.Stream
            .concat(AUTH_PATHS.stream(), java.util.stream.Stream.of(REAUTHENTICATION_PATH))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    @Test
    void platformAudienceHasOnlyTheFiveApprovedOperationsAndNoSignup() throws Exception {
        Map<String, Object> feature = document("platform-operator-auth/openapi.yaml");
        Map<String, Object> paths = map(feature.get("paths"));
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(AUTH_PATHS);
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
        assertThat(audiencePaths.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_AUDIENCE_PATHS);
        assertThat(audiencePaths.values()).allSatisfy(value -> assertThat(map(value)).containsOnlyKeys("$ref"));
        assertThat(map(document("mvp1-openapi.yaml").get("paths")).keySet())
                .doesNotContainAnyElementsOf(EXPECTED_AUDIENCE_PATHS);
        for (var entry : audiencePaths.entrySet()) {
            String ref = (String) map(entry.getValue()).get("$ref");
            String feature = entry.getKey().equals(REAUTHENTICATION_PATH)
                    ? "platform-operator-authorization"
                    : "platform-operator-auth";
            assertThat(ref).startsWith("./" + feature + "/openapi.yaml#/paths/");
            assertThat(map(document(feature + "/openapi.yaml").get("paths"))).containsKey(entry.getKey());
        }
    }

    @Test
    void runtimeControllerMethodsCannotDriftFromStaticOperations() throws Exception {
        Map<String, Object> paths = map(document("platform-operator-auth/openapi.yaml").get("paths"));
        Map<String, String> runtime = new LinkedHashMap<>();
        for (var method : PlatformOperatorAuthController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostMapping.class))
                runtime.put("post " + method.getAnnotation(PostMapping.class).value()[0], method.getName());
            if (method.isAnnotationPresent(GetMapping.class))
                runtime.put("get " + method.getAnnotation(GetMapping.class).value()[0], method.getName());
            if (method.isAnnotationPresent(DeleteMapping.class))
                runtime.put("delete " + method.getAnnotation(DeleteMapping.class).value()[0], method.getName());
            if (method.isAnnotationPresent(PutMapping.class))
                runtime.put("put " + method.getAnnotation(PutMapping.class).value()[0], method.getName());
        }
        Map<String, String> contract = new LinkedHashMap<>();
        paths.forEach((path, item) -> map(item).forEach((verb, operation) ->
                contract.put(verb + " " + path.substring("/api/v1/platform-operators/auth".length()),
                        (String) map(operation).get("operationId"))));

        Map<String, String> expected = Map.of(
                "post /sessions", "createPlatformOperatorSession",
                "post /token-refreshes", "refreshPlatformOperatorToken",
                "get /csrf-tokens/current", "getCurrentPlatformOperatorCsrfToken",
                "delete /sessions/current", "deleteCurrentPlatformOperatorSession",
                "put /initial-password", "replacePlatformOperatorInitialPassword");
        assertThat(runtime.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        assertThat(contract).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(runtime).containsEntry("post /sessions", "login")
                .containsEntry("post /token-refreshes", "refresh")
                .containsEntry("get /csrf-tokens/current", "csrfToken")
                .containsEntry("delete /sessions/current", "logout")
                .containsEntry("put /initial-password", "changeInitialPassword");
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
