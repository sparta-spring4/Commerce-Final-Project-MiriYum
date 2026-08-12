package com.miriyum.domain.reservation.waiting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class WaitingOpenApiContractTest {

    private static final Path CONTRACT = Path.of(
            "..", "docs", "specs", "waiting", "openapi.yaml");
    private static final String SETTINGS_PATH =
            "/api/v1/store-operators/stores/{storeId}/waiting-settings";
    private static final String DISABLE_IMPACT_PATH = SETTINGS_PATH + "/disable-impact";
    private static final String IDEMPOTENCY_KEY =
            "../mvp1-common/openapi.yaml#/components/parameters/IdempotencyKey";

    @Test
    void storeOperatorWaitingSettingsKeepTheApprovedContract() throws IOException {
        Map<String, Object> document = load(CONTRACT);

        Map<String, Object> paths = map(document.get("paths"));
        assertThat(paths).containsOnlyKeys(SETTINGS_PATH, DISABLE_IMPACT_PATH);

        Map<String, Object> settingsPath = map(paths.get(SETTINGS_PATH));
        assertThat(settingsPath).containsKeys("get", "put");

        Map<String, Object> updateOperation = map(settingsPath.get("put"));
        assertThat(list(updateOperation.get("security"))).isNotEmpty();
        assertThat(list(updateOperation.get("parameters"))).anySatisfy(parameter ->
                assertThat(map(parameter)).containsEntry("$ref", IDEMPOTENCY_KEY));
        assertThat(map(updateOperation.get("responses")))
                .containsKeys("200", "400", "403", "404", "409", "429");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> waitingSettingProperties =
                map(map(schemas.get("WaitingSetting")).get("properties"));
        assertThat(waitingSettingProperties).containsOnlyKeys(
                "storeId", "enabled", "receptionMode", "advanceOpenMinutes", "version");
        assertThat(map(waitingSettingProperties.get("enabled")))
                .containsEntry("default", false);
        assertThat(map(waitingSettingProperties.get("receptionMode")))
                .containsEntry("default", "PAUSED");
        assertThat(map(waitingSettingProperties.get("advanceOpenMinutes")))
                .containsEntry("minimum", 0)
                .containsEntry("maximum", 180)
                .containsEntry("default", 60);
        assertThat(map(waitingSettingProperties.get("version")))
                .containsEntry("default", 0);

        assertThat(list(map(waitingSettingProperties.get("receptionMode")).get("enum")))
                .containsExactly("AUTO", "MANUAL", "PAUSED");

        Map<String, Object> updateProperties =
                map(map(schemas.get("WaitingSettingUpdateRequest")).get("properties"));
        assertThat(updateProperties).containsOnlyKeys(
                "expectedVersion",
                "enabled",
                "receptionMode",
                "advanceOpenMinutes",
                "disableAction");
        assertThat(list(map(updateProperties.get("receptionMode")).get("enum")))
                .containsExactly("AUTO", "MANUAL", "PAUSED");
        assertThat(map(updateProperties.get("advanceOpenMinutes")))
                .containsEntry("minimum", 0)
                .containsEntry("maximum", 180);

        assertThat(list(map(updateProperties.get("disableAction")).get("enum")))
                .containsExactly("KEEP_ACTIVE", "CLOSE_ACTIVE_TEAMS");

        String serialized = new Yaml().dump(document);
        assertThat(serialized)
                .doesNotContain("radiusMeters", "radiusKilometers", "1000", "5000");
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
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
