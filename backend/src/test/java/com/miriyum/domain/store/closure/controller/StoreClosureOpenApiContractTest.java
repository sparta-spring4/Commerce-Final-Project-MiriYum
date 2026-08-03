package com.miriyum.domain.store.closure.controller;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StoreClosureOpenApiContractTest {
    @Test void documentsAllSixOperatorRoutesAndNoBatchHttpRoute() throws Exception {
        String yaml = Files.readString(Path.of("..", "docs", "specs", "store-search", "openapi.yaml"));
        assertThat(yaml).contains(
                "/api/v1/store-operator/stores/{storeId}/regular-closures:",
                "/api/v1/store-operator/stores/{storeId}/regular-closures/{version}/publication:",
                "/api/v1/store-operator/stores/{storeId}/regular-closures/{version}/publication-cancellation:",
                "/api/v1/store-operator/stores/{storeId}/temporary-closures:",
                "/api/v1/store-operator/stores/{storeId}/temporary-closures/{closureId}/end-at:",
                "/api/v1/store-operator/stores/{storeId}/temporary-closures/{closureId}/cancellation:");
        assertThat(yaml).doesNotContain("validateServiceIntervals");
    }
}
