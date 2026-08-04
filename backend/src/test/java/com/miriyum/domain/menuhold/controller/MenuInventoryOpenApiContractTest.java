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
    void operatorRoutesAndOvernightDatesMatchTheHttpContract() throws IOException {
        Path contract = Path.of("..", "docs", "specs",
                "menu-hold-pickup", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        Map<String, Object> paths = map(document.get("paths"));
        assertThat(map(paths.get(
                "/api/v1/store-operator/stores/{storeId}/menu-inventory-buckets")))
                .containsKeys("get", "post");
        assertThat(map(paths.get(
                "/api/v1/store-operator/stores/{storeId}/menu-inventory-buckets/{inventoryBucketId}")))
                .containsKey("patch");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> create = map(schemas.get("MenuInventoryCreateRequest"));
        Map<String, Object> response = map(schemas.get("MenuInventoryBucket"));
        assertThat(list(create.get("required"))).contains("endDate");
        assertThat(map(create.get("properties"))).containsKey("endDate");
        assertThat(list(response.get("required"))).contains("endDate");
        assertThat(map(response.get("properties"))).containsKey("endDate");
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
