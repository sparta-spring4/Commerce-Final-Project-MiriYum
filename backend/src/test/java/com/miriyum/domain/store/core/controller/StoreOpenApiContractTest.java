package com.miriyum.domain.store.core.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class StoreOpenApiContractTest {

    @Test
    void managedStoreGeocodingAndFailureResponsesMatchControllerContract()
            throws IOException {
        Path contract = Path.of("..", "docs", "specs", "store-search", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> collection = map(paths.get("/api/v1/store-operator/stores"));
        assertThat(map(map(collection.get("post")).get("responses")))
                .containsKeys("400", "503");

        Map<String, Object> item = map(paths.get("/api/v1/store-operator/stores/{storeId}"));
        assertThat(map(map(item.get("patch")).get("responses")))
                .containsKeys("400", "409", "503");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> managedStore = map(schemas.get("ManagedStore"));
        assertThat(list(managedStore.get("required"))).contains("geocoding");
        assertThat(map(map(managedStore.get("properties")).get("geocoding")))
                .containsEntry("$ref", "#/components/schemas/StoreGeocoding");
        assertThat(map(map(schemas.get("StoreDetail")).get("properties")))
                .doesNotContainKey("geocoding");

        Map<String, Object> geocoding = map(schemas.get("StoreGeocoding"));
        assertThat(geocoding).containsEntry("additionalProperties", false);
        assertThat(list(geocoding.get("required"))).containsExactly(
                "status",
                "latitude",
                "longitude",
                "verifiedAddress",
                "verifiedAt",
                "addressVersion");
        Map<String, Object> properties = map(geocoding.get("properties"));
        assertThat(list(map(properties.get("status")).get("enum")))
                .containsExactly("UNVERIFIED", "VERIFIED");
        assertThat(map(properties.get("addressVersion"))).containsEntry("minimum", 1);
        assertThat(properties).doesNotContainKeys(
                "provider", "providerVersion", "providerResponse");
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
