package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.bind.annotation.RequestMethod;

class OpenApiRouteInventoryTest {

    @TempDir
    Path specsRoot;

    @Test
    void loadsOnlyFeatureOperationsAndSeparatesValidContractOnlyRoutes() throws IOException {
        write("reservation/openapi.yaml", """
                openapi: 3.1.0
                paths:
                  /api/v1/consumers/me/reservations:
                    post:
                      responses: {}
                  /api/v1/consumers/me/reservations/{reservationId}/cancellations:
                    x-miriyum-runtime-status: contract-only
                    x-miriyum-owner-issue: 271
                    put:
                      responses: {}
                """);
        write("consumer-openapi.yaml", documentWithGet("/ignored/root-entrypoint"));
        write("mvp1-common/openapi.yaml", documentWithGet("/ignored/common"));
        write("_template/openapi.yaml", documentWithGet("/ignored/template"));

        OpenApiRouteInventory inventory = OpenApiRouteInventory.load(specsRoot);

        assertThat(inventory.activeRoutes()).containsExactly(new ApiRoute(
                RequestMethod.POST,
                "/api/v1/consumers/me/reservations"));
        assertThat(inventory.contractOnlyRoutes()).containsExactly(new ApiRoute(
                RequestMethod.PUT,
                "/api/v1/consumers/me/reservations/{reservationId}/cancellations"));
        assertThat(inventory.metadataErrors()).isEmpty();
        assertThat(inventory.staleContractOnlyRoutes(Set.of(new ApiRoute(
                RequestMethod.PUT,
                "/api/v1/consumers/me/reservations/{reservationId}/cancellations"))))
                .containsExactly(new ApiRoute(
                        RequestMethod.PUT,
                        "/api/v1/consumers/me/reservations/{reservationId}/cancellations"));
    }

    @Test
    void reportsIncompleteOrInvalidContractOnlyMetadata() throws IOException {
        write("waiting/openapi.yaml", """
                openapi: 3.1.0
                paths:
                  /api/v1/a:
                    x-miriyum-runtime-status: contract-only
                    get:
                      responses: {}
                  /api/v1/b:
                    x-miriyum-owner-issue: 271
                    post:
                      responses: {}
                  /api/v1/c:
                    x-miriyum-runtime-status: contract-only
                    x-miriyum-owner-issue: 0
                    put:
                      responses: {}
                """);

        OpenApiRouteInventory inventory = OpenApiRouteInventory.load(specsRoot);

        assertThat(inventory.metadataErrors())
                .hasSize(3)
                .anyMatch(error -> error.contains("/api/v1/a") && error.contains("owner-issue"))
                .anyMatch(error -> error.contains("/api/v1/b") && error.contains("runtime-status"))
                .anyMatch(error -> error.contains("/api/v1/c") && error.contains("positive"));
    }

    @Test
    void rejectsContractOnlyMetadataDeclaredOnAnOperation() throws IOException {
        write("waiting/openapi.yaml", """
                openapi: 3.1.0
                paths:
                  /api/v1/store-operators/stores/{storeId}/waiting-settings:
                    put:
                      x-miriyum-runtime-status: contract-only
                      x-miriyum-owner-issue: 271
                      responses: {}
                """);

        OpenApiRouteInventory inventory = OpenApiRouteInventory.load(specsRoot);

        assertThat(inventory.metadataErrors())
                .singleElement()
                .asString()
                .contains("operation-level", "PUT", "waiting-settings");
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = specsRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String documentWithGet(String path) {
        return """
                openapi: 3.1.0
                paths:
                  %s:
                    get:
                      responses: {}
                """.formatted(path);
    }
}
