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
    void menuPublicationConditionsDeclareEffectiveAtInEachLocalSchema()
            throws IOException {
        Path contract = Path.of("..", "docs", "specs", "store-search", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> request = map(schemas.get("MenuPublicationRequest"));
        List<Object> conditions = list(request.get("allOf"));

        Map<String, Object> scheduled = map(map(conditions.get(0)).get("then"));
        assertThat(map(scheduled.get("properties"))).containsKey("effectiveAt");
        assertThat(list(scheduled.get("required"))).containsExactly("effectiveAt");

        Map<String, Object> immediate = map(
                map(map(conditions.get(1)).get("then")).get("not"));
        assertThat(map(immediate.get("properties"))).containsKey("effectiveAt");
        assertThat(list(immediate.get("required"))).containsExactly("effectiveAt");
    }

    @Test
    void managementLifecycleAndStructuredDisclosureMatchControllerContract()
            throws IOException {
        Path contract = Path.of("..", "docs", "specs", "store-search", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        Map<String, Object> paths = map(document.get("paths"));
        assertThat(map(paths.get("/api/v1/store-operators/stores/{storeId}/menus")))
                .containsKeys("get", "post");
        assertThat(map(paths.get("/api/v1/store-operators/stores/{storeId}/menus/{menuId}")))
                .containsKeys("get", "put");
        assertThat(paths).containsKeys(
                "/api/v1/store-operators/stores/{storeId}/menus/{menuId}/publications",
                "/api/v1/store-operators/stores/{storeId}/menus/{menuId}/publication-cancellations",
                "/api/v1/store-operators/stores/{storeId}/menus/{menuId}/visibility",
                "/api/v1/store-operators/stores/{storeId}/menus/{menuId}/selling-status",
                "/api/v1/store-operators/stores/{storeId}/menus/{menuId}/retirements");

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

    @Test
    void menuImageSlotMatchesPublicImageUploadContract() throws IOException {
        Path contract = Path.of("..", "docs", "specs", "store-search", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> imageSlot = map(
                paths.get("/api/v1/store-operators/stores/{storeId}/menus/{menuId}/images"));
        assertThat(imageSlot).containsEntry("x-miriyum-runtime-status", "contract-only");
        assertThat(imageSlot).containsEntry("x-miriyum-owner-issue", 349);
        assertThat(imageSlot).containsKeys("put", "delete");

        Map<String, Object> put = map(imageSlot.get("put"));
        assertThat(put).containsEntry("operationId", "putMenuImage");
        assertThat(list(put.get("security"))).containsExactly(Map.of("bearerAuth", List.of()));
        assertThat(list(put.get("parameters"))).contains(
                Map.of("$ref", "#/components/parameters/StoreId"),
                Map.of("$ref", "#/components/parameters/MenuId"),
                Map.of("$ref", "../mvp1-common/openapi.yaml#/components/parameters/IdempotencyKey"));
        assertThat(map(map(put.get("requestBody")).get("content")))
                .containsKey("multipart/form-data");
        assertThat(map(put.get("responses"))).containsEntry("200", Map.of(
                "description", "저장 또는 교체를 완료한 메뉴 공개 이미지",
                "content", Map.of("application/json", Map.of(
                        "schema", Map.of("$ref", "#/components/schemas/MenuPublicImageSuccessResponse")))));

        Map<String, Object> delete = map(imageSlot.get("delete"));
        assertThat(delete).containsEntry("operationId", "deleteMenuImage");
        assertThat(list(delete.get("parameters"))).contains(
                Map.of("$ref", "#/components/parameters/StoreId"),
                Map.of("$ref", "#/components/parameters/MenuId"),
                Map.of("$ref", "../mvp1-common/openapi.yaml#/components/parameters/IdempotencyKey"));
        assertThat(map(delete.get("responses"))).containsKeys("204", "400");

        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> menuImage = map(schemas.get("MenuPublicImage"));
        assertThat(list(menuImage.get("required"))).containsExactly("url");
        assertThat(map(menuImage.get("properties"))).containsOnlyKeys("url");
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
