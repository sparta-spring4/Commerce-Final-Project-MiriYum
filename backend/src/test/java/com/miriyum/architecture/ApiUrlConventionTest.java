package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

class ApiUrlConventionTest {

    private static final Path SPECS_ROOT = Path.of("..", "docs", "specs");

    @Test
    void rejectsMisplacedAudienceScopeAndSingularResources() {
        Set<ApiRoute> invalid = Set.of(
                route(RequestMethod.GET, "/api/v1/consumers/reservations"),
                route(RequestMethod.GET, "/api/v1/consumers/profile/me"),
                route(RequestMethod.GET, "/api/v1/payments/me"),
                route(RequestMethod.POST, "/api/v1/stores/auth"),
                route(RequestMethod.GET, "/api/v1/webhooks/me"),
                route(RequestMethod.POST, "/api/v1/store-operators/stores/{storeId}/publication"),
                route(RequestMethod.GET, "/api/v1/stores/{storeId}/menu"));

        assertThat(ApiUrlConvention.violations(invalid))
                .hasSize(7)
                .anyMatch(message -> message.contains("/consumers/reservations")
                        && message.contains("scope"))
                .anyMatch(message -> message.contains("/profile/me")
                        && message.contains("position"))
                .anyMatch(message -> message.contains("/payments/me")
                        && message.contains("position"))
                .anyMatch(message -> message.contains("/stores/auth")
                        && message.contains("position"))
                .anyMatch(message -> message.contains("/webhooks/me")
                        && message.contains("position"))
                .anyMatch(message -> message.contains("/publication")
                        && message.contains("legacy"))
                .anyMatch(message -> message.contains("/menu")
                        && message.contains("plural"));
    }

    @Test
    void rejectsEveryLegacyPathShapeFromTheMigrationTable() {
        Set<ApiRoute> legacy = Set.of(
                route(RequestMethod.POST, "/api/v1/store-operators/stores/{storeId}/menus/{menuId}/publication"),
                route(RequestMethod.POST, "/api/v1/store-operators/stores/{storeId}/menus/{menuId}/publication-cancellation"),
                route(RequestMethod.POST, "/api/v1/store-operators/stores/{storeId}/menus/{menuId}/retirement"),
                route(RequestMethod.POST, "/api/v1/store-operators/stores/{storeId}/temporary-closures/{closureId}/cancellation"),
                route(RequestMethod.POST, "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/call"),
                route(RequestMethod.POST, "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/arrive"),
                route(RequestMethod.POST, "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/check-in"),
                route(RequestMethod.POST, "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/cancel"),
                route(RequestMethod.GET, "/api/v1/store-operators/stores/{storeId}/waiting-close-jobs/{jobId}"),
                route(RequestMethod.GET, "/api/v1/store-operators/stores/{storeId}/waiting-settings/disable-impact"),
                route(RequestMethod.POST, "/api/v1/stores/{storeId}/menus/{menuId}/alternatives/search"));

        assertThat(ApiUrlConvention.violations(legacy)).hasSize(legacy.size());
    }

    @Test
    void acceptsApprovedSingletonAndProviderSegments() {
        Set<ApiRoute> valid = Set.of(
                route(RequestMethod.PUT, "/api/v1/consumers/me/contact"),
                route(RequestMethod.POST, "/api/v1/consumers/auth/kakao/sessions"),
                route(RequestMethod.PATCH, "/api/v1/store-operators/stores/{storeId}/menus/{menuId}/visibility"),
                route(RequestMethod.PUT, "/api/v1/store-operators/stores/{storeId}/temporary-closures/{closureId}/end-at"),
                route(RequestMethod.GET, "/api/v1/store-operators/stores/{storeId}/waiting-settings/deactivation-impact"),
                route(RequestMethod.POST, "/api/v1/platform-operators/auth/sessions"),
                route(RequestMethod.PUT, "/api/v1/platform-operators/accounts/{operatorId}/authority"),
                route(RequestMethod.PUT, "/api/v1/platform-operators/accounts/{operatorId}/suspension"),
                route(RequestMethod.GET, "/api/v1/consumers/me/notifications/unread-count"),
                route(RequestMethod.POST, "/api/v1/payments/webhooks/portone"));

        assertThat(ApiUrlConvention.violations(valid)).isEmpty();
    }

    @Test
    void runtimeAndAllFeatureOpenApiRoutesFollowTheSameConvention() throws IOException {
        Set<ApiRoute> runtimeRoutes = SpringMvcRouteInventory.routes().stream()
                .map(ControllerRoute::route)
                .filter(route -> route.path().startsWith("/api/v1/"))
                .collect(Collectors.toUnmodifiableSet());
        OpenApiRouteInventory openApi = OpenApiRouteInventory.load(SPECS_ROOT);
        Set<ApiRoute> allOpenApiRoutes = new java.util.HashSet<>(openApi.activeRoutes());
        allOpenApiRoutes.addAll(openApi.contractOnlyRoutes());

        assertThat(ApiUrlConvention.violations(runtimeRoutes))
                .as("Spring MVC URL convention violations")
                .isEmpty();
        assertThat(ApiUrlConvention.violations(allOpenApiRoutes))
                .as("feature OpenAPI URL convention violations, including contract-only")
                .isEmpty();
    }

    private static ApiRoute route(RequestMethod method, String path) {
        return new ApiRoute(method, path);
    }
}
