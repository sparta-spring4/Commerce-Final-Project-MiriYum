package com.miriyum.domain.platformoperator;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.platformoperator.controller.auth.PlatformOperatorAuthController;
import com.miriyum.domain.platformoperator.controller.authorization.PlatformOperatorCapabilitiesController;
import com.miriyum.domain.platformoperator.controller.management.PlatformOperatorAccountQueryController;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private static final String CAPABILITIES_PATH = "/api/v1/platform-operators/me";
    private static final Set<String> MEMBER_SUPPORT_PATHS = Set.of(
            "/api/v1/platform-operators/members",
            "/api/v1/platform-operators/members/{accountType}/{accountId}",
            "/api/v1/platform-operators/member-support-cases",
            "/api/v1/platform-operators/member-support-cases/{caseId}",
            "/api/v1/platform-operators/member-support-cases/{caseId}/assignments",
            "/api/v1/platform-operators/member-support-cases/{caseId}/decisions",
            "/api/v1/platform-operators/members/{accountType}/{accountId}/sanctions",
            "/api/v1/platform-operators/member-sanctions/{sanctionId}/additional-approvals");
    private static final Set<String> MANAGEMENT_AUDIT_PATHS = Set.of(
            "/api/v1/platform-operators/accounts",
            "/api/v1/platform-operators/accounts/{operatorId}",
            "/api/v1/platform-operators/accounts/{operatorId}/authority",
            "/api/v1/platform-operators/accounts/{operatorId}/suspension",
            "/api/v1/platform-operators/audit-events",
            "/api/v1/platform-operators/audit-events/{eventKey}",
            "/api/v1/platform-operators/audit-events/{eventKey}/corrections");
    private static final Set<String> ADMIN_STORE_PATHS = Set.of(
            "/api/v1/platform-operators/stores",
            "/api/v1/platform-operators/stores/{storeId}",
            "/api/v1/platform-operators/stores/{storeId}/sanction-cases",
            "/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/assignments",
            "/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}",
            "/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/impact-previews",
            "/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/sanctions",
            "/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/sanctions/{sanctionId}/approvals",
            "/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/sanctions/{sanctionId}/releases");
    private static final Set<String> EXPECTED_AUDIENCE_PATHS = java.util.stream.Stream
            .concat(java.util.stream.Stream.concat(
                            AUTH_PATHS.stream(), java.util.stream.Stream.of(REAUTHENTICATION_PATH)),
                    java.util.stream.Stream.concat(
                            java.util.stream.Stream.concat(
                                    MEMBER_SUPPORT_PATHS.stream(), java.util.stream.Stream.of(CAPABILITIES_PATH)),
                            java.util.stream.Stream.concat(
                                    MANAGEMENT_AUDIT_PATHS.stream(), ADMIN_STORE_PATHS.stream())))
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
            String feature;
            if (entry.getKey().equals(REAUTHENTICATION_PATH)) {
                feature = "platform-operator-authorization";
            } else if (entry.getKey().equals(CAPABILITIES_PATH)) {
                feature = "platform-operator-capabilities";
            } else if (MEMBER_SUPPORT_PATHS.contains(entry.getKey())) {
                feature = "member-support";
            } else if (MANAGEMENT_AUDIT_PATHS.contains(entry.getKey())) {
                feature = "platform-operator-management-audit";
            } else if (ADMIN_STORE_PATHS.contains(entry.getKey())) {
                feature = "admin-store";
            } else {
                feature = "platform-operator-auth";
            }
            assertThat(ref).startsWith("./" + feature + "/openapi.yaml#/paths/");
            assertThat(map(document(feature + "/openapi.yaml").get("paths"))).containsKey(entry.getKey());
        }
    }

    @Test
    void capabilitiesContractExposesOnlyCurrentAuthoritySnapshot() throws Exception {
        Map<String, Object> capabilities = document("platform-operator-capabilities/openapi.yaml");
        Map<String, Object> paths = map(capabilities.get("paths"));
        assertThat(paths).containsOnlyKeys(CAPABILITIES_PATH);
        assertThat(map(map(paths.get(CAPABILITIES_PATH)).get("get")))
                .containsEntry("operationId", "getCurrentPlatformOperatorCapabilities");

        Map<String, Object> schemas = map(map(capabilities.get("components")).get("schemas"));
        Map<String, Object> properties = map(map(schemas.get("PlatformOperatorCapabilitiesData")).get("properties"));
        assertThat(properties).containsOnlyKeys("authorityVersion", "roles", "permissions");
        assertThat(list(map(schemas.get("PlatformOperatorRole")).get("enum")))
                .containsExactlyInAnyOrder(
                        java.util.Arrays.stream(PlatformOperatorRole.values()).map(Enum::name).toArray());
        assertThat(list(map(schemas.get("PlatformOperatorPermission")).get("enum")))
                .containsExactlyInAnyOrder(
                        java.util.Arrays.stream(PlatformOperatorPermission.values()).map(Enum::name).toArray());

        String responses = map(map(capabilities.get("components")).get("responses")).toString();
        assertThat(responses).contains(
                "AUTH_001", "AUTH_002", "AUTH_003", "AUTH_004", "AUTH_015", "AUTH_011", "AUTH_012");
    }

    @Test
    void sharedAdminReasonCodeIncludesStoreEnforcement() throws Exception {
        Map<String, Object> management = document("platform-operator-management-audit/openapi.yaml");
        Map<String, Object> schemas = map(map(management.get("components")).get("schemas"));
        assertThat(list(map(schemas.get("AuditReason")).get("enum"))).contains("STORE_ENFORCEMENT");
    }

    @Test
    void accountReadContractDefinesSafeQueriesAndSecretFreeResponses() throws Exception {
        Map<String, Object> management = document("platform-operator-management-audit/openapi.yaml");
        Map<String, Object> paths = map(management.get("paths"));
        assertThat(paths).doesNotContainKey(CAPABILITIES_PATH);
        assertThat(map(map(paths.get("/api/v1/platform-operators/accounts")).get("get")))
                .containsEntry("operationId", "searchPlatformOperatorAccounts");
        assertThat(map(map(paths.get("/api/v1/platform-operators/accounts/{operatorId}")).get("get")))
                .containsEntry("operationId", "getPlatformOperatorAccount");

        Map<String, Object> componentResponses = map(map(management.get("components")).get("responses"));
        assertThat(map(map(componentResponses.get("AuthorizationDenied")).get("content"))
                .toString()).contains("AUTH_012", "ADMIN_001");

        Map<String, Object> schemas = map(map(management.get("components")).get("schemas"));
        assertThat(schemas).containsKeys(
                "OperatorAccountSummary", "OperatorAccountPage", "OperatorAccountDetail");
        assertThat(schemas).doesNotContainKeys("CurrentOperatorData", "CurrentOperatorResponse");
        assertThat(map(map(schemas.get("OperatorAccountSummary")).get("properties")).keySet())
                .containsExactlyInAnyOrder("operatorId", "email", "displayName", "status",
                        "passwordChangeRequired", "authorityVersion", "roles", "lastLoginAt");
        assertThat(map(map(schemas.get("OperatorAccountDetail")).get("properties")).keySet())
                .containsExactlyInAnyOrder("operatorId", "email", "displayName", "status",
                        "passwordChangeRequired", "authorityVersion", "roles", "directPermissions",
                        "effectivePermissions", "lastLoginAt")
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("passwordhash")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("token")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("session")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("approval"));
    }

    @Test
    void accountReadRuntimeMethodsCannotDriftFromStaticOperations() throws Exception {
        Map<String, Object> paths = map(document("platform-operator-management-audit/openapi.yaml").get("paths"));
        Map<String, String> runtime = new LinkedHashMap<>();
        for (var method : PlatformOperatorAccountQueryController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                runtime.put("get /api/v1/platform-operators"
                        + method.getAnnotation(GetMapping.class).value()[0], method.getName());
            }
        }

        Map<String, String> contract = new LinkedHashMap<>();
        runtime.keySet().forEach(operation -> {
            String path = operation.substring("get ".length());
            contract.put(operation, (String) map(map(paths.get(path)).get("get")).get("operationId"));
        });

        assertThat(runtime).containsExactlyInAnyOrderEntriesOf(Map.of(
                "get /api/v1/platform-operators/accounts", "search",
                "get /api/v1/platform-operators/accounts/{operatorId}", "detail"));
        assertThat(contract).containsExactlyInAnyOrderEntriesOf(Map.of(
                "get /api/v1/platform-operators/accounts", "searchPlatformOperatorAccounts",
                "get /api/v1/platform-operators/accounts/{operatorId}", "getPlatformOperatorAccount"));
    }

    @Test
    void capabilitiesRuntimeMethodCannotDriftFromStaticOperation() throws Exception {
        Map<String, Object> paths = map(document("platform-operator-capabilities/openapi.yaml").get("paths"));
        Map<String, String> runtime = new LinkedHashMap<>();
        for (var method : PlatformOperatorCapabilitiesController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                runtime.put("get /api/v1/platform-operators"
                        + method.getAnnotation(GetMapping.class).value()[0], method.getName());
            }
        }
        Map<String, String> contract = new LinkedHashMap<>();
        runtime.keySet().forEach(operation -> {
            String path = operation.substring("get ".length());
            contract.put(operation, (String) map(map(paths.get(path)).get("get")).get("operationId"));
        });

        assertThat(runtime).containsExactly(
                Map.entry("get /api/v1/platform-operators/me", "current"));
        assertThat(contract).containsExactly(
                Map.entry("get /api/v1/platform-operators/me", "getCurrentPlatformOperatorCapabilities"));
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
