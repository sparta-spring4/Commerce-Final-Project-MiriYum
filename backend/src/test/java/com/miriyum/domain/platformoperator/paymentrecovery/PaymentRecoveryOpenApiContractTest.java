package com.miriyum.domain.platformoperator.paymentrecovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PaymentRecoveryOpenApiContractTest {
    private static final Path CONTRACT = Path.of(
            "..", "docs", "specs", "payment-recovery", "openapi.yaml");
    private static final Set<String> PATHS = Set.of(
            "/api/v1/platform-operators/payment-recovery-cases",
            "/api/v1/platform-operators/payment-recovery-cases/pending-additional-approvals",
            "/api/v1/platform-operators/payment-recovery-cases/{caseId}",
            "/api/v1/platform-operators/payment-recovery-cases/{caseId}/assignments",
            "/api/v1/platform-operators/payment-recovery-cases/{caseId}/requeries",
            "/api/v1/platform-operators/payment-recovery-cases/{caseId}/proposals",
            "/api/v1/platform-operators/payment-recovery-cases/{caseId}/proposals/{proposalVersion}/approvals",
            "/api/v1/platform-operators/payment-recovery-cases/{caseId}/failed-unresolved-closures");

    @Test
    void exposesOnlyVersionedRecoveryWorkflowWithoutOperatorExecuteEndpoint() throws Exception {
        Map<String, Object> paths = map(document().get("paths"));

        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(PATHS);
        assertThat(paths).doesNotContainKeys(
                "/api/v1/platform-operators/payment-recovery-cases/{caseId}/executions",
                "/api/v1/platform-operators/payment-recovery-cases/{caseId}/payments");
        assertThat(map(paths.get("/api/v1/platform-operators/payment-recovery-cases")))
                .containsOnlyKeys("get");
        assertThat(map(paths.get("/api/v1/platform-operators/payment-recovery-cases/{caseId}")))
                .containsOnlyKeys("get");
        assertThat(map(paths.get(
                "/api/v1/platform-operators/payment-recovery-cases/pending-additional-approvals")))
                .containsOnlyKeys("get");
    }

    @Test
    void mutationsRequireIdempotencyReauthenticationCorrelationAndExpectedVersions() throws Exception {
        Map<String, Object> contract = document();
        Map<String, Object> paths = map(contract.get("paths"));
        Map<String, Object> schemas = map(map(contract.get("components")).get("schemas"));
        for (String path : PATHS) {
            Map<String, Object> item = map(paths.get(path));
            if (!item.containsKey("post")) continue;
            Map<String, Object> post = map(item.get("post"));
            String operation = post.toString();
            assertThat(operation)
                    .contains("IdempotencyKey", "AdminReauthentication", "CorrelationId");
            Map<String, Object> content = map(map(post.get("requestBody")).get("content"));
            String schemaRef = (String) map(map(content.get("application/json")).get("schema")).get("$ref");
            String schemaName = schemaRef.substring("#/components/schemas/".length());
            assertThat(map(map(schemas.get(schemaName)).get("properties")))
                    .containsKey("expectedCaseVersion");
        }
        Map<String, Object> parameters = map(map(contract.get("components")).get("parameters"));
        assertThat(map(parameters.get("IdempotencyKey"))).containsEntry("name", "Idempotency-Key");
        assertThat(map(parameters.get("AdminReauthentication")))
                .containsEntry("name", "X-Admin-Reauthentication");
        assertThat(map(parameters.get("CorrelationId"))).containsEntry("name", "X-Correlation-Id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void caseSummaryPublishesEveryVersionRequiredByFollowUpCommands() throws Exception {
        Map<String, Object> schemas = map(map(document().get("components")).get("schemas"));
        Map<String, Object> summary = map(schemas.get("CaseSummary"));

        assertThat((List<String>) summary.get("required"))
                .contains("caseVersion", "handoffVersion", "paymentVersion", "recoveryVersion",
                        "assignedOperatorId", "assignedToCurrentOperator");
        assertThat(map(summary.get("properties")))
                .containsKeys("caseVersion", "handoffVersion", "paymentVersion", "recoveryVersion",
                        "assignedOperatorId", "assignedToCurrentOperator");
    }

    @Test
    void responseAndAuditShapedSchemasExcludeRawProviderAndSecretFields() throws Exception {
        Map<String, Object> schemas = map(map(document().get("components")).get("schemas"));
        String normalized = schemas.toString().toLowerCase(Locale.ROOT);

        assertThat(normalized)
                .contains("maskedproviderreference", "originalamountminor", "caseversion")
                .doesNotContain(
                        "providerpaymentid", "providertransactionid", "providerpayload",
                        "cardnumber", "cardtoken", "bankaccount", "authorization",
                        "password", "secret", "rawresponse");
    }

    private static Map<String, Object> document() throws Exception {
        assertThat(CONTRACT).as("payment-recovery OpenAPI must exist")
                .satisfies(path -> assertThat(Files.isRegularFile(path)).isTrue());
        try (InputStream input = Files.newInputStream(CONTRACT)) {
            return map(new Yaml().load(input));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
