package com.miriyum.domain.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class RepresentativeMenuOpenApiContractTest {

    private static final String PATH =
            "/api/v1/store-operators/stores/{storeId}/representative-menus";

    @Test
    void operatorContractReplacesThreeToFiveRepresentativeMenusInOrder()
            throws IOException {
        Map<String, Object> document = load();
        Map<String, Object> path = map(map(document.get("paths")).get(PATH));

        assertThat(path).containsKeys("get", "put");
        Map<String, Object> put = map(path.get("put"));
        assertThat(list(put.get("parameters")))
                .anySatisfy(parameter -> assertThat(map(parameter))
                        .containsEntry("$ref",
                                "../mvp1-common/openapi.yaml#/components/parameters/IdempotencyKey"));

        Map<String, Object> schemas = schemas(document);
        Map<String, Object> request = map(schemas.get("RepresentativeMenuReplaceRequest"));
        assertThat(list(request.get("required")))
                .containsExactlyInAnyOrder("expectedVersion", "menuIds");
        Map<String, Object> menuIds = map(map(request.get("properties")).get("menuIds"));
        assertThat(menuIds)
                .containsEntry("minItems", 3)
                .containsEntry("maxItems", 5)
                .containsEntry("uniqueItems", true);
        assertThat(map(menuIds.get("items")))
                .containsEntry("$ref",
                        "../mvp1-common/openapi.yaml#/components/schemas/PublicId");
    }

    @Test
    void currentRepresentativeMenuContractKeepsExistingPublicNameAndStates()
            throws IOException {
        Map<String, Object> document = load();
        Map<String, Object> schemas = schemas(document);
        Map<String, Object> setting = map(schemas.get("RepresentativeMenuSetting"));
        Map<String, Object> properties = map(setting.get("properties"));

        assertThat(list(setting.get("required")))
                .containsExactlyInAnyOrder("version", "status", "items");
        assertThat(map(properties.get("status")))
                .containsEntry("$ref", "#/components/schemas/RepresentativeMenuSettingStatus");
        assertThat(list(map(schemas.get("RepresentativeMenuSettingStatus")).get("enum")))
                .containsExactly("UNCONFIGURED", "CONFIGURED", "REQUIRES_ATTENTION");
        assertThat(map(schemas.get("StoreDetail")).toString())
                .contains("representativeMenus");
        assertThat(document.toString()).doesNotContain("popularMenus");
    }

    private static Map<String, Object> load() throws IOException {
        Path contract = Path.of("..", "docs", "specs", "store-search", "openapi.yaml");
        try (InputStream input = Files.newInputStream(contract)) {
            return map(new Yaml().load(input));
        }
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
