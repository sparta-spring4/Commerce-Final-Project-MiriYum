package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ControllerOpenApiContractTest {

    private static final Path SPECS_ROOT = Path.of("..", "docs", "specs");

    @Test
    void activeFeatureOpenApiOperationsExactlyMatchSpringRoutes() throws IOException {
        Set<ApiRoute> runtimeRoutes = runtimeRoutes();
        OpenApiRouteInventory openApi = OpenApiRouteInventory.load(SPECS_ROOT);

        assertThat(openApi.metadataErrors()).as("invalid contract-only metadata").isEmpty();
        assertThat(openApi.staleContractOnlyRoutes(runtimeRoutes))
                .as("contract-only operations that already have Spring routes")
                .isEmpty();
        assertThat(openApi.activeRoutes()).as("active OpenAPI and Spring route drift")
                .isEqualTo(runtimeRoutes);
    }

    private static Set<ApiRoute> runtimeRoutes() {
        return SpringMvcRouteInventory.routes().stream()
                .map(ControllerRoute::route)
                .filter(route -> route.path().startsWith("/api/v1/"))
                .collect(Collectors.toUnmodifiableSet());
    }
}
