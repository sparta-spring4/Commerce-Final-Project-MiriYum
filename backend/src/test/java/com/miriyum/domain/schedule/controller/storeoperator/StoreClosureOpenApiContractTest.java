package com.miriyum.domain.schedule.controller.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class StoreClosureOpenApiContractTest {
    @Test void documentsAllSixOperatorRoutesAndNoBatchHttpRoute() throws Exception {
        String yaml = Files.readString(Path.of("..", "docs", "specs", "store-search", "openapi.yaml"));
        assertThat(yaml).contains(
                "/api/v1/store-operators/stores/{storeId}/regular-closures:",
                "/api/v1/store-operators/stores/{storeId}/regular-closures/{version}/publication:",
                "/api/v1/store-operators/stores/{storeId}/regular-closures/{version}/publication-cancellation:",
                "/api/v1/store-operators/stores/{storeId}/temporary-closures:",
                "/api/v1/store-operators/stores/{storeId}/temporary-closures/{closureId}/end-at:",
                "/api/v1/store-operators/stores/{storeId}/temporary-closures/{closureId}/cancellation:");
        assertThat(yaml).doesNotContain("validateServiceIntervals");
    }

    @Test
    void temporaryClosureEndChangeRequiresReason() throws Exception {
        Path contract = Path.of("..", "docs", "specs", "store-search", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> request = map(schemas.get("TemporaryClosureEndAtRequest"));

        assertThat(list(request.get("required"))).containsExactly("endAt", "changeReason");
        assertThat(map(request.get("properties"))).containsKeys("endAt", "changeReason");
        assertThat(map(map(request.get("properties")).get("changeReason")))
                .containsEntry("minLength", 1)
                .containsEntry("maxLength", 500);
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
