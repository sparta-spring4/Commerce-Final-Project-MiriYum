package com.miriyum.domain.menuhold.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MenuInventoryOpenApiContractTest {

    @Test
    void menuHoldAvailabilityUsesServerResolvedIntervalContract() throws IOException {
        Path contract = Path.of("..", "docs", "specs", "menu-hold-pickup", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }
        Map<String, Object> operation = map(map(map(document.get("paths")).get(
                "/api/v1/stores/{storeId}/menu-hold-availability")).get("get"));
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) operation.get("parameters");
        assertThat(parameters.stream().map(parameter -> parameter.get("name")))
                .contains("serviceDate", "startTime", "startOffset")
                .doesNotContain("endTime");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> data = map(schemas.get("MenuHoldAvailabilityData"));
        assertThat(map(data.get("properties")))
                .containsKeys("serviceDate", "startAt", "serviceEndAt", "timeZoneId", "items");
    }

    @Test
    void operatorRoutesAndOvernightDatesMatchTheHttpContract() throws IOException {
        Path contract = Path.of("..", "docs", "specs",
                "menu-hold-pickup", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> collectionRoute = map(paths.get(
                "/api/v1/store-operator/stores/{storeId}/menu-inventory-buckets"));
        assertThat(collectionRoute).containsKeys("get", "post");
        assertMenuNotFoundResponse(map(collectionRoute.get("get")));
        assertMenuNotFoundResponse(map(collectionRoute.get("post")));
        assertThat(map(paths.get(
                "/api/v1/store-operator/stores/{storeId}/menu-inventory-buckets/{inventoryBucketId}")))
                .containsKey("patch");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> create = map(schemas.get("MenuInventoryCreateRequest"));
        Map<String, Object> update = map(schemas.get("MenuInventoryUpdateRequest"));
        Map<String, Object> response = map(schemas.get("MenuInventoryBucket"));
        assertThat(list(create.get("required")))
                .contains("endDate", "sharedOnlineAllowed");
        assertThat(map(create.get("properties")))
                .containsKeys("endDate", "sharedOnlineAllowed");
        assertThat(list(update.get("required"))).contains("sharedOnlineAllowed");
        assertThat(map(update.get("properties"))).containsKey("sharedOnlineAllowed");
        assertThat(list(response.get("required")))
                .contains("endDate", "sharedOnlineAllowed");
        assertThat(map(response.get("properties")))
                .containsKeys("endDate", "sharedOnlineAllowed");
    }

    private static void assertMenuNotFoundResponse(Map<String, Object> operation) {
        Map<String, Object> responses = map(operation.get("responses"));
        assertThat(map(responses.get("404")))
                .containsEntry("$ref", "#/components/responses/MenuNotFound");
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
