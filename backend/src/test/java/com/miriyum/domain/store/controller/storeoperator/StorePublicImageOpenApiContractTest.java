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

class StorePublicImageOpenApiContractTest {

    @Test
    void publicStoreImageOperationsUseOperatorMultipartEndpoints() throws IOException {
        Map<String, Object> document = openApi();
        Map<String, Object> paths = map(document.get("paths"));

        assertMultipartUpload(paths, "/api/v1/store-operators/stores/{storeId}/images", "post");
        assertThat(map(paths.get("/api/v1/store-operators/stores/{storeId}/images/{imageId}")))
                .containsKeys("put", "delete");
    }

    @Test
    void publicImageResponsesExposeOnlyApiPathAndNeverStorageMetadata() throws IOException {
        Map<String, Object> schemas = map(map(openApi().get("components")).get("schemas"));
        Map<String, Object> publicImage = map(schemas.get("PublicImage"));

        assertThat(list(publicImage.get("required"))).containsExactly("imageId", "url");
        assertThat(map(publicImage.get("properties"))).containsOnlyKeys("imageId", "url");
        assertThat(map(map(publicImage.get("properties")).get("url")))
                .containsEntry("pattern", "^/api/v1/public-files/[0-9a-fA-F-]{36}$");
    }

    @Test
    void publicImageContractMatchesIdempotentDeleteAndStorageFailureBehavior() throws IOException {
        Map<String, Object> paths = map(openApi().get("paths"));

        Map<String, Object> publicFileResponses = responses(paths, "/api/v1/public-files/{imageId}", "get");
        assertThat(publicFileResponses).containsKeys("200", "404", "503");

        Map<String, Object> storeDeleteResponses = responses(
                paths, "/api/v1/store-operators/stores/{storeId}/images/{imageId}", "delete");
        assertThat(storeDeleteResponses).containsKey("204").doesNotContainKey("404");
    }

    private void assertMultipartUpload(Map<String, Object> paths, String path, String method) {
        Map<String, Object> operation = map(map(paths.get(path)).get(method));
        Map<String, Object> content = map(map(operation.get("requestBody")).get("content"));
        Map<String, Object> schema = map(content.get("multipart/form-data"));
        Map<String, Object> properties = map(map(schema.get("schema")).get("properties"));

        assertThat(map(properties.get("file")))
                .containsEntry("type", "string")
                .containsEntry("format", "binary");
    }

    private Map<String, Object> responses(Map<String, Object> paths, String path, String method) {
        Object responses = map(map(paths.get(path)).get(method)).get("responses");
        if (responses instanceof Map<?, ?> responseMap) {
            return map(responseMap);
        }
        throw new IllegalStateException("responses가 없습니다.");
    }

    private Map<String, Object> openApi() throws IOException {
        Path contract = Path.of("..", "docs", "specs", "store-search", "openapi.yaml");
        try (InputStream input = Files.newInputStream(contract)) {
            return new Yaml().load(input);
        }
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
