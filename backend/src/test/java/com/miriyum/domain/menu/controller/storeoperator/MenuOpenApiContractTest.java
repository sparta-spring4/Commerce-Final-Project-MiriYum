package com.miriyum.domain.menu.controller.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MenuOpenApiContractTest {

    @Test
    void managementLifecycleAndStructuredDisclosureMatchControllerContract()
            throws IOException {
        Path contract = Path.of("..", "docs", "specs", "store-search", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        Map<String, Object> paths = map(document.get("paths"));
        assertThat(map(paths.get("/api/v1/store-operator/stores/{storeId}/menus")))
                .containsKeys("get", "post");
        assertThat(map(paths.get("/api/v1/store-operator/stores/{storeId}/menus/{menuId}")))
                .containsKeys("get", "put");
        assertThat(paths).containsKeys(
                "/api/v1/store-operator/stores/{storeId}/menus/{menuId}/publication",
                "/api/v1/store-operator/stores/{storeId}/menus/{menuId}/publication-cancellation",
                "/api/v1/store-operator/stores/{storeId}/menus/{menuId}/visibility",
                "/api/v1/store-operator/stores/{storeId}/menus/{menuId}/selling-status",
                "/api/v1/store-operator/stores/{storeId}/menus/{menuId}/retirement");

        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> menuWrite = map(schemas.get("MenuWriteRequest"));
        assertThat(list(menuWrite.get("required"))).contains(
                "holdSelectionAllowed", "pickupSelectionAllowed",
                "allergenInformationStatus", "allergenDisclosures",
                "originInformationStatus", "originDisclosures", "alcoholic");
        assertThat(map(menuWrite.get("properties")))
                .doesNotContainKeys("holdEnabled", "pickupEnabled", "versionStatus",
                        "visibility", "saleStatus");

        Map<String, Object> managedMenu = map(schemas.get("ManagedMenu"));
        assertThat(map(managedMenu.get("properties")))
                .containsKeys("menuId", "storeId", "visibility", "sellingStatus",
                        "retired", "draft", "scheduled", "published");
        assertThat(list(map(map(managedMenu.get("properties"))
                .get("sellingStatus")).get("enum")))
                .containsExactly("SELLING", "SOLD_OUT", "PAUSED");
        assertThat(map(map(managedMenu.get("properties")).get("menuId")))
                .containsEntry("$ref",
                        "../mvp1-common/openapi.yaml#/components/schemas/PublicId");
        assertThat(map(map(managedMenu.get("properties")).get("storeId")))
                .containsEntry("$ref",
                        "../mvp1-common/openapi.yaml#/components/schemas/PublicId");

        Map<String, Object> allergen = map(schemas.get("AllergenDisclosure"));
        assertThat(list(map(map(allergen.get("properties"))
                .get("ingredientCode")).get("enum")))
                .hasSize(19)
                .contains("EGG", "MILK", "SHELLFISH", "PINE_NUT");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        return (List<String>) value;
    }
}
