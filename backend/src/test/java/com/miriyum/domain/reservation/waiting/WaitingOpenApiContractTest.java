package com.miriyum.domain.reservation.waiting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        assertThat(settingsPath).containsOnlyKeys("get", "put");

        Map<String, Object> settingsQuery = map(settingsPath.get("get"));
        assertThat(map(settingsQuery.get("responses")).keySet())
                .containsExactlyInAnyOrder(
                        "200", "400", "401", "403", "404", "409", "429");

        Map<String, Object> disableImpactPath = map(paths.get(DISABLE_IMPACT_PATH));
        assertThat(disableImpactPath).containsOnlyKeys("get");
        Map<String, Object> disableImpactQuery = map(disableImpactPath.get("get"));
        assertThat(map(disableImpactQuery.get("responses")).keySet())
                .containsExactlyInAnyOrder(
                        "200", "400", "401", "403", "404", "409", "429");

        Map<String, Object> updateOperation = map(settingsPath.get("put"));
        assertThat(list(updateOperation.get("security"))).isNotEmpty();
        assertThat(list(updateOperation.get("parameters"))).anySatisfy(parameter ->
                assertThat(map(parameter)).containsEntry("$ref", IDEMPOTENCY_KEY));
        assertThat(map(updateOperation.get("responses")).keySet())
                .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "409", "429");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> waitingSettingProperties =
                map(map(schemas.get("WaitingSetting")).get("properties"));
        assertThat(waitingSettingProperties).containsOnlyKeys(
                "storeId", "enabled", "receptionMode", "advanceOpenMinutes", "version");
        assertThat(map(waitingSettingProperties.get("advanceOpenMinutes")))
                .containsEntry("minimum", 0)
                .containsEntry("maximum", 180);

        Map<String, Object> updateProperties =
                map(map(schemas.get("WaitingSettingUpdateRequest")).get("properties"));
        assertThat(updateProperties).containsOnlyKeys(
                "expectedVersion",
                "enabled",
                "receptionMode",
                "advanceOpenMinutes",
                "disableAction");
        assertThat(map(updateProperties.get("advanceOpenMinutes")))
                .containsEntry("minimum", 0)
                .containsEntry("maximum", 180);

        String serialized = new Yaml().dump(document);
        assertThat(serialized)
                .doesNotContain("radiusMeters", "radiusKilometers", "1000", "5000");
    }

    @Test
    void waitingSchemasReferenceCanonicalEnumsWithoutInlineCopies() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        assertThat(list(map(schemas.get("WaitingReceptionMode")).get("enum")))
                .containsExactly("AUTO", "MANUAL", "PAUSED");
        assertThat(list(map(schemas.get("WaitingDisableAction")).get("enum")))
                .containsExactly("KEEP_ACTIVE", "CLOSE_ACTIVE_TEAMS");

        Map<String, Object> settingProperties =
                map(map(schemas.get("WaitingSetting")).get("properties"));
        assertThat(map(settingProperties.get("receptionMode")))
                .containsOnlyKeys("$ref")
                .containsEntry("$ref", "#/components/schemas/WaitingReceptionMode");

        Map<String, Object> updateProperties =
                map(map(schemas.get("WaitingSettingUpdateRequest")).get("properties"));
        assertThat(map(updateProperties.get("receptionMode")))
                .containsOnlyKeys("$ref")
                .containsEntry("$ref", "#/components/schemas/WaitingReceptionMode");
        assertThat(map(updateProperties.get("disableAction")))
                .containsOnlyKeys("$ref")
                .containsEntry("$ref", "#/components/schemas/WaitingDisableAction");
    }

    @Test
    void waitingResponsesExposeTheCurrentSettingVersionUnderOneName() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        Map<String, Object> settingProperties =
                map(map(schemas.get("WaitingSetting")).get("properties"));
        Map<String, Object> impactProperties =
                map(map(schemas.get("WaitingDisableImpact")).get("properties"));

        assertThat(settingProperties).containsKey("version");
        assertThat(impactProperties)
                .containsKey("version")
                .doesNotContainKey("settingVersion");
        assertThat(map(impactProperties.get("version")))
                .containsEntry("type", "integer")
                .containsEntry("format", "int64")
                .containsEntry("minimum", 0);
    }

    @Test
    void requiredWaitingSettingResponseFieldsDoNotDeclareDefaults() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> setting = map(schemas.get("WaitingSetting"));
        Map<String, Object> properties = map(setting.get("properties"));

        assertThat(list(setting.get("required")))
                .contains("enabled", "receptionMode", "advanceOpenMinutes", "version");
        assertThat(List.of("enabled", "receptionMode", "advanceOpenMinutes", "version"))
                .allSatisfy(property ->
                        assertThat(map(properties.get(property))).doesNotContainKey("default"));
    }

    @Test
    void waitingSettingSchemasEnforceEnabledModeAndDisableActionInvariants() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        Map<String, Object> waitingSetting = map(schemas.get("WaitingSetting"));
        assertThat(list(waitingSetting.get("allOf")))
                .hasSize(1)
                .anySatisfy(rule -> assertConditionalConst(
                        map(rule), "enabled", false, "receptionMode", "PAUSED"));

        Map<String, Object> updateRequest = map(schemas.get("WaitingSettingUpdateRequest"));
        List<Object> updateRules = list(updateRequest.get("allOf"));
        assertThat(updateRules)
                .hasSize(2)
                .anySatisfy(rule -> assertConditionalConst(
                        map(rule), "enabled", false, "receptionMode", "PAUSED"))
                .anySatisfy(rule -> {
                    Map<String, Object> condition = map(map(rule).get("if"));
                    assertThat(map(condition.get("properties")))
                            .containsKey("disableAction");
                    assertThat(list(condition.get("required")))
                            .containsExactly("disableAction");
                    Map<String, Object> consequence = map(map(rule).get("then"));
                    Map<String, Object> enabled = map(
                            map(consequence.get("properties")).get("enabled"));
                    assertThat(enabled).containsEntry("const", false);
                });
    }

    @Test
    void waitingOperationsExposeCanonicalValidationAuthorityAndStoreStateFailures()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));

        Map<String, Object> settingsPath = map(paths.get(SETTINGS_PATH));
        assertResponseReference(
                map(settingsPath.get("get")),
                "400",
                "#/components/responses/WaitingStoreIdBadRequest");
        assertResponseReference(
                map(settingsPath.get("get")),
                "409",
                "#/components/responses/WaitingStoreStateConflict");
        assertResponseReference(
                map(map(paths.get(DISABLE_IMPACT_PATH)).get("get")),
                "400",
                "#/components/responses/WaitingStoreIdBadRequest");
        assertResponseReference(
                map(map(paths.get(DISABLE_IMPACT_PATH)).get("get")),
                "409",
                "#/components/responses/WaitingStoreStateConflict");

        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> parameters = map(components.get("parameters"));
        Map<String, Object> storeId = map(parameters.get("StoreId"));
        assertThat(map(storeId.get("schema"))).containsEntry(
                "$ref", "../mvp1-common/openapi.yaml#/components/schemas/PublicId");

        Map<String, Object> responses = map(components.get("responses"));
        assertThat(responseExampleCodes(map(responses.get("WaitingStoreIdBadRequest"))))
                .containsExactly("COMMON_001");
        assertThat(responseExampleCodes(map(responses.get("WaitingStoreForbidden"))))
                .containsExactlyInAnyOrder("AUTH_011", "STORE_003");
        assertThat(responseExampleCodes(map(responses.get("WaitingStoreStateConflict"))))
                .containsExactly("STORE_007");
        assertThat(responseExampleCodes(map(responses.get("WaitingSettingConflict"))))
                .containsExactlyInAnyOrder(
                        "WAITING_001",
                        "WAITING_002",
                        "COMMON_007",
                        "COMMON_008",
                        "STORE_005",
                        "STORE_007");
    }

    private static void assertConditionalConst(
            Map<String, Object> rule,
            String conditionProperty,
            Object conditionValue,
            String consequenceProperty,
            Object consequenceValue
    ) {
        Map<String, Object> condition = map(map(rule.get("if")).get("properties"));
        assertThat(map(condition.get(conditionProperty)))
                .containsEntry("const", conditionValue);
        assertThat(list(map(rule.get("if")).get("required")))
                .containsExactly(conditionProperty);

        Map<String, Object> consequence = map(map(rule.get("then")).get("properties"));
        assertThat(map(consequence.get(consequenceProperty)))
                .containsEntry("const", consequenceValue);
    }

    private static void assertResponseReference(
            Map<String, Object> operation,
            String status,
            String reference
    ) {
        assertThat(map(map(operation.get("responses")).get(status)))
                .containsEntry("$ref", reference);
    }

    private static List<String> responseExampleCodes(Map<String, Object> response) {
        Map<String, Object> json = map(map(response.get("content")).get("application/json"));
        List<String> codes = new ArrayList<>();

        if (json.containsKey("example")) {
            codes.add((String) map(json.get("example")).get("code"));
        }
        if (json.containsKey("examples")) {
            for (Object example : map(json.get("examples")).values()) {
                codes.add((String) map(map(example).get("value")).get("code"));
            }
        }
        return List.copyOf(codes);
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
