package com.miriyum.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AnalyticsOpenApiContractTest {

    private static final Path CONTRACT = Path.of(
            "..", "docs", "specs", "analytics", "openapi.yaml");
    private static final String DASHBOARD_PATH =
            "/api/v1/store-operators/stores/{storeId}/dashboard-statistics";

    @Test
    void dashboardSnapshotIsAnActiveStoreOperatorGet() throws IOException {
        assertThat(CONTRACT).isRegularFile();

        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));
        assertThat(paths).containsOnlyKeys(DASHBOARD_PATH);

        Map<String, Object> operation = map(map(paths.get(DASHBOARD_PATH)).get("get"));
        assertThat(operation)
                .containsEntry("operationId", "getStoreDashboardStatistics")
                .doesNotContainKeys("x-miriyum-runtime-status", "x-miriyum-owner-issue");
        assertThat(map(operation.get("responses"))).containsKey("200");
    }

    @Test
    void successSnapshotRequiresSixIndependentMetricsWithFreshnessMetadata()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        assertThat(schemas).containsKeys(
                "DashboardSnapshotData",
                "CountMetric",
                "RateMetric",
                "WaitingMetric",
                "NoShowMetric");
        Map<String, Object> data = map(schemas.get("DashboardSnapshotData"));
        Map<String, Object> dataProperties = map(data.get("properties"));

        assertThat(set(data.get("required"))).containsExactlyInAnyOrder(
                "snapshotId",
                "storeId",
                "businessDate",
                "timeZoneId",
                "asOf",
                "generatedAt",
                "storeAuthorityVersion",
                "metrics");

        Map<String, Object> metrics = map(dataProperties.get("metrics"));
        Map<String, Object> metricProperties = map(metrics.get("properties"));
        assertThat(set(metrics.get("required"))).containsExactlyInAnyOrder(
                "todayReservationTeams",
                "reservationRate",
                "teamCapacityUsageRate",
                "cancellationRate",
                "waiting",
                "noShow");

        assertThat(metricProperties).containsOnlyKeys(
                "todayReservationTeams",
                "reservationRate",
                "teamCapacityUsageRate",
                "cancellationRate",
                "waiting",
                "noShow");

        for (String schemaName : List.of(
                "CountMetric",
                "RateMetric",
                "WaitingMetric",
                "NoShowMetric")) {
            Map<String, Object> metric = map(schemas.get(schemaName));
            assertThat(set(metric.get("required"))).contains(
                    "definitionVersion",
                    "aggregationVersion",
                    "asOf",
                    "dataThrough",
                    "inputCheckpoint",
                    "completeness",
                    "corrected");
        }
    }

    @Test
    void noShowKeepsReservationGapsSeparateFromWaitingConfirmedCounts()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> noShowValue = map(schemas.get("NoShowValue"));
        Map<String, Object> noShowProperties = map(noShowValue.get("properties"));

        assertThat(noShowProperties)
                .containsOnlyKeys(
                        "reservationCandidate",
                        "reservationConfirmed",
                        "waitingConfirmed")
                .doesNotContainKey("total");

        Map<String, Object> response = map(
                map(components.get("responses")).get("DashboardSnapshotSuccess"));
        Map<String, Object> json = map(map(response.get("content")).get("application/json"));
        assertThat(json).containsKey("examples");
        Map<String, Object> examples = map(json.get("examples"));
        assertThat(examples).containsKey("reservationNoShowPending");
        Map<String, Object> example = map(examples.get("reservationNoShowPending"));
        Map<String, Object> data = map(map(example.get("value")).get("data"));
        Map<String, Object> metrics = map(data.get("metrics"));
        Map<String, Object> noShow = map(metrics.get("noShow"));
        Map<String, Object> categories = map(noShow.get("value"));

        assertUnavailableBecauseContractMissing(map(categories.get("reservationCandidate")));
        assertThat(map(categories.get("reservationConfirmed")))
                .containsEntry("value", 1)
                .containsEntry("completeness", "COMPLETE")
                .containsEntry("reasonCode", null);
        assertThat(map(categories.get("waitingConfirmed")))
                .containsEntry("value", 2)
                .containsEntry("completeness", "COMPLETE")
                .containsEntry("reasonCode", null);
        assertThat(noShow)
                .containsEntry("definitionVersion", "analytics-004-no-show-v2")
                .containsEntry("completeness", "PARTIAL")
                .containsEntry("reasonCode", "SOURCE_CONTRACT_MISSING");
    }

    @Test
    void accessErrorsDistinguishForeignStoreFromMissingPublicStore() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> operation = map(map(map(document.get("paths"))
                .get(DASHBOARD_PATH)).get("get"));
        Map<String, Object> responses = map(operation.get("responses"));

        assertThat(map(responses.get("403")))
                .containsEntry("$ref", "#/components/responses/AnalyticsStoreForbidden");
        assertThat(map(responses.get("404")))
                .containsEntry("$ref", "#/components/responses/AnalyticsStoreNotFound");

        Map<String, Object> responseComponents = map(
                map(document.get("components")).get("responses"));
        assertThat(exampleCodes(map(responseComponents.get("AnalyticsStoreForbidden"))))
                .containsExactlyInAnyOrder("AUTH_011", "STORE_003");
        assertThat(exampleCodes(map(responseComponents.get("AnalyticsStoreNotFound"))))
                .containsExactly("STORE_001");
    }

    private static void assertUnavailableBecauseContractMissing(Map<String, Object> metric) {
        assertThat(metric)
                .containsEntry("value", null)
                .containsEntry("completeness", "UNAVAILABLE")
                .containsEntry("reasonCode", "SOURCE_CONTRACT_MISSING");
    }

    private static List<String> exampleCodes(Map<String, Object> response) {
        Map<String, Object> json = map(map(response.get("content")).get("application/json"));
        if (json.containsKey("example")) {
            return List.of((String) map(json.get("example")).get("code"));
        }
        return map(json.get("examples")).values().stream()
                .map(AnalyticsOpenApiContractTest::map)
                .map(example -> map(example.get("value")))
                .map(value -> (String) value.get("code"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return (Map<String, Object>) new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> set(Object value) {
        return Set.copyOf((List<Object>) value);
    }
}
