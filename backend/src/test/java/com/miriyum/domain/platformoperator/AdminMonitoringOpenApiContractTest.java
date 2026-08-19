package com.miriyum.domain.platformoperator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AdminMonitoringOpenApiContractTest {
    private static final Path SPECS = Path.of("..", "docs", "specs");
    private static final String LIST = "/api/v1/platform-operators/admin-monitoring/cases";
    private static final String DETAIL =
            "/api/v1/platform-operators/admin-monitoring/cases/{caseType}/{caseId}";

    @Test
    void monitoringPathsAreContractOnlyPlatformOperatorRoutes() throws Exception {
        Map<String, Object> featurePaths = paths("admin-monitoring/openapi.yaml");
        Map<String, Object> audiencePaths = paths("platform-operator-openapi.yaml");

        assertThat(featurePaths).containsKeys(LIST, DETAIL);
        assertThat(audiencePaths).containsKeys(LIST, DETAIL);
        assertThat(reference(audiencePaths, LIST))
                .isEqualTo("./admin-monitoring/openapi.yaml#/paths/"
                        + "~1api~1v1~1platform-operators~1admin-monitoring~1cases");
        assertThat(reference(audiencePaths, DETAIL))
                .isEqualTo("./admin-monitoring/openapi.yaml#/paths/"
                        + "~1api~1v1~1platform-operators~1admin-monitoring~1cases~1{caseType}~1{caseId}");

        for (String path : List.of(LIST, DETAIL)) {
            Map<String, Object> get = map(map(featurePaths.get(path)).get("get"));
            assertThat(get).containsEntry("x-miriyum-runtime-status", "contract-only")
                    .containsEntry("x-miriyum-owner-issue", 280);
            assertThat(map(get.get("responses")).keySet())
                    .contains("200", "400", "401", "403", "404", "503");
        }
    }

    @Test
    void listContractFixesFiltersSortCursorAndPartialFailure() throws Exception {
        Map<String, Object> document = document("admin-monitoring/openapi.yaml");
        Map<String, Object> get = map(map(paths(document).get(LIST)).get("get"));
        Set<String> parameterNames = list(get.get("parameters")).stream()
                .map(AdminMonitoringOpenApiContractTest::map)
                .map(parameter -> parameterName(document, parameter))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(parameterNames).containsExactlyInAnyOrder(
                "storeId", "caseTypes", "lifecycleStatuses", "sourceStatuses",
                "reconciliationStatuses", "changedFrom", "changedTo", "size", "cursor");

        Map<String, Object> schemas = schemas(document);
        Map<String, Object> page = map(schemas.get("AdminMonitoringCasePage"));
        assertThat(list(page.get("required"))).contains(
                "items", "asOf", "dataThrough", "completeness", "failures");
        assertThat(map(map(page.get("properties")).get("nextCursor")))
                .containsEntry("type", List.of("string", "null"));

        Map<String, Object> summary = map(schemas.get("AdminMonitoringCaseSummary"));
        assertThat(list(summary.get("required"))).contains(
                "caseType", "caseId", "statusChangedAt", "caseVersion", "ledgers");
        assertThat(map(summary.get("properties")).keySet())
                .doesNotContain("name", "phone", "email", "paymentMethod", "consumerId");
    }

    @Test
    void ledgerAndDetailContractsPreserveSourceTruthAndMasking() throws Exception {
        Map<String, Object> schemas = schemas(document("admin-monitoring/openapi.yaml"));
        Map<String, Object> ledger = map(schemas.get("AdminMonitoringLedgerCell"));
        assertThat(list(ledger.get("required"))).containsExactlyInAnyOrder(
                "source", "state", "asOf",
                "dataThrough", "completeness", "reconciliationStatus");
        Map<String, Object> ledgerState = map(schemas.get("AdminMonitoringLedgerState"));
        assertThat(list(ledgerState.get("required"))).containsExactlyInAnyOrder(
                "sourceStatus", "statusVersion", "statusChangedAt");
        assertThat(list(map(map(ledger.get("properties")).get("state")).get("oneOf")))
                .anySatisfy(candidate -> assertThat(map(candidate))
                        .containsEntry("type", "null"));

        Map<String, Object> failure = map(schemas.get("AdminMonitoringDependencyFailure"));
        assertThat(list(failure.get("required")))
                .containsExactlyInAnyOrder("source", "errorCode", "retryable");

        Map<String, Object> detail = map(schemas.get("AdminMonitoringCaseDetail"));
        assertThat(list(detail.get("required")))
                .contains("caseType", "caseId", "maskingLevel", "ledgers", "history");
        assertThat(map(map(detail.get("properties")).get("maskingLevel")))
                .containsEntry("enum", List.of("MINIMIZED"));
        assertThat(map(detail.get("properties")).keySet())
                .doesNotContain("rawName", "rawPhone", "rawEmail", "paymentKey", "providerTransactionId");
    }

    private static String reference(Map<String, Object> paths, String path) {
        return (String) map(paths.get(path)).get("$ref");
    }

    private static String parameterName(Map<String, Object> document, Map<String, Object> parameter) {
        if (parameter.containsKey("name")) {
            return (String) parameter.get("name");
        }
        String reference = (String) parameter.get("$ref");
        String componentName = reference.substring(reference.lastIndexOf('/') + 1);
        Map<String, Object> parameters = map(map(document.get("components")).get("parameters"));
        return (String) map(parameters.get(componentName)).get("name");
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

    private static Map<String, Object> schemas(Map<String, Object> document) {
        return map(map(document.get("components")).get("schemas"));
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
