package com.miriyum.domain.store.controller.storeoperator;

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
    void managedStoreCollectionDefinesOwnedStoreListResponse() throws IOException {
        Path contract = Path.of("..", "docs", "specs", "store-search", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> collection =
                map(paths.get("/api/v1/store-operators/stores"));
        assertThat(collection).containsKey("get");

        Map<String, Object> listOperation = map(collection.get("get"));
        Map<String, Object> success =
                map(map(listOperation.get("responses")).get("200"));
        Map<String, Object> responseSchema = map(map(
                map(success.get("content")).get("application/json")).get("schema"));
        assertThat(responseSchema)
                .containsEntry("$ref", "#/components/schemas/ManagedStoreListSuccessResponse");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> listResponse = map(schemas.get("ManagedStoreListSuccessResponse"));
        Map<String, Object> data = map(map(listResponse.get("properties")).get("data"));
        assertThat(data).containsEntry("type", "array");
        assertThat(map(data.get("items")))
                .containsEntry("$ref", "#/components/schemas/ManagedStore");
    }

    @Test
    void storeRegistrationRequiresJsonApplicationAndPrivateEvidence()
            throws IOException {
        Map<String, Object> document = document(Path.of(
                "..", "docs", "specs", "store-onboarding", "openapi.yaml"));
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> post = map(map(
                paths.get("/api/v1/store-operators/stores")).get("post"));
        Map<String, Object> multipart = map(map(post.get("requestBody")).get("content"));

        assertThat(multipart).containsKey("multipart/form-data");
        Map<String, Object> multipartBody = map(multipart.get("multipart/form-data"));
        Map<String, Object> schema = map(multipartBody.get("schema"));
        assertThat(list(schema.get("required")))
                .containsExactly("application", "businessRegistrationEvidence");
        assertThat(map(map(schema.get("properties")).get("businessRegistrationEvidence")))
                .containsEntry("type", "string")
                .containsEntry("format", "binary");
        assertThat(map(map(multipartBody.get("encoding")).get("application")))
                .containsEntry("contentType", "application/json");
        assertThat(map(post.get("responses"))).containsKey("202");
    }

    @Test
    void managedStoreGeocodingAndFailureResponsesMatchControllerContract()
            throws IOException {
        Map<String, Object> onboardingDocument = document(Path.of(
                "..", "docs", "specs", "store-onboarding", "openapi.yaml"));
        Map<String, Object> onboardingPaths = map(onboardingDocument.get("paths"));
        Map<String, Object> registration =
                map(onboardingPaths.get("/api/v1/store-operators/stores"));
        assertThat(map(map(registration.get("post")).get("responses")))
                .containsKeys("400", "503");

        Path contract = Path.of("..", "docs", "specs", "store-search", "openapi.yaml");
        Map<String, Object> document = document(contract);

        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> item = map(paths.get("/api/v1/store-operators/stores/{storeId}"));
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

    private static Map<String, Object> document(Path contract) throws IOException {
        try (InputStream input = Files.newInputStream(contract)) {
            return map(new Yaml().load(input));
        }
    }
}
