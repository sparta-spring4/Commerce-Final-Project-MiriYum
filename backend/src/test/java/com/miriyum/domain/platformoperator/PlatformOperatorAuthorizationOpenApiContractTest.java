package com.miriyum.domain.platformoperator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PlatformOperatorAuthorizationOpenApiContractTest {
    private static final Path SPECS = Path.of("..", "docs", "specs");
    private static final String PATH = "/api/v1/platform-operators/reauthentication-approvals";

    @Test
    void authorizationPathIsExposedOnlyByPlatformOperatorAudience() throws Exception {
        assertThat(paths("platform-operator-openapi.yaml")).containsKey(PATH);
        assertThat(paths("mvp1-openapi.yaml")).doesNotContainKey(PATH);
        assertThat(paths("consumer-openapi.yaml")).doesNotContainKey(PATH);
        assertThat(paths("store-operator-openapi.yaml")).doesNotContainKey(PATH);
    }

    @Test
    void reauthenticationApprovalBindsPasswordPurposeAndTarget() throws Exception {
        Map<String, Object> document = document("platform-operator-authorization/openapi.yaml");
        Map<String, Object> operation = map(map(paths(document).get(PATH)).get("post"));
        assertThat(operation).containsEntry("operationId", "createPlatformOperatorReauthenticationApproval");
        assertThat(map(operation.get("responses"))).containsKeys("200", "400", "401", "403", "503");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> request = map(schemas.get("ReauthenticationApprovalRequest"));
        assertThat(request).containsEntry("additionalProperties", false);
        assertThat(list(request.get("required")))
                .containsExactlyInAnyOrder("currentPassword", "purpose", "targetType", "targetId");

        Map<String, Object> response = map(schemas.get("ReauthenticationApprovalData"));
        assertThat(list(response.get("required"))).containsExactlyInAnyOrder("approval", "expiresAt");
    }

    @Test
    void approvalHeaderHasSingleUseBindingDescription() throws Exception {
        Map<String, Object> document = document("platform-operator-authorization/openapi.yaml");
        Map<String, Object> parameters = map(map(document.get("components")).get("parameters"));
        Map<String, Object> approval = map(parameters.get("AdminReauthentication"));
        assertThat(approval).containsEntry("name", "X-Admin-Reauthentication")
                .containsEntry("in", "header")
                .containsEntry("required", true);
        assertThat((String) approval.get("description")).contains("5분", "목적", "대상", "세션");
    }

    @Test
    void forbiddenResponseDocumentsLimitedSessionAndAuthorizationDenialCodes() throws Exception {
        Map<String, Object> document = document("platform-operator-authorization/openapi.yaml");
        Map<String, Object> responses = map(map(document.get("components")).get("responses"));
        Map<String, Object> forbidden = map(responses.get("AuthorizationDenied"));
        Map<String, Object> content = map(map(forbidden.get("content")).get("application/json"));
        Map<String, Object> examples = map(content.get("examples"));

        assertThat(map(map(examples.get("InitialPasswordChangeRequired")).get("value")))
                .containsEntry("code", "AUTH_012");
        assertThat(map(map(examples.get("AuthorizationDenied")).get("value")))
                .containsEntry("code", "ADMIN_001");
    }

    private static Map<String, Object> document(String file) throws Exception {
        try (InputStream input = Files.newInputStream(SPECS.resolve(file))) {
            return map(new Yaml().load(input));
        }
    }

    private static Map<String, Object> paths(String file) throws Exception {
        return paths(document(file));
    }

    private static Map<String, Object> paths(Map<String, Object> document) {
        return map(document.get("paths"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
