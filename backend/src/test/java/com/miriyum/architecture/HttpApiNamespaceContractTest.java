package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

class HttpApiNamespaceContractTest {

    private static final String CONSUMER_ROOT = "/api/v1/consumers";
    private static final String STORE_OPERATOR_ROOT = "/api/v1/store-operators";
    private static final String PLATFORM_OPERATOR_ROOT = "/api/v1/platform-operators";
    private static final Set<String> LEGACY_ROOTS = Set.of(
            "/api/v1/consumer-auth",
            "/api/v1/consumer-accounts",
            "/api/v1/reservations",
            "/api/v1/pickup-reservations",
            "/api/v1/store-operator-auth",
            "/api/v1/store-operator-accounts",
            "/api/v1/store-operator");

    @Test
    void audienceControllersUseOnlyCanonicalRoots() {
        Set<ControllerRoute> routes = SpringMvcRouteInventory.routes();

        assertAudienceNamespaces(routes);
    }

    @Test
    void platformOperatorManagementAndAuditControllersExposeTheApprovedRoutes() {
        Set<ApiRoute> routes = SpringMvcRouteInventory.routes().stream()
                .filter(HttpApiNamespaceContractTest::isPlatformOperator)
                .map(ControllerRoute::route)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(routes).contains(
                new ApiRoute(RequestMethod.POST, "/api/v1/platform-operators/accounts"),
                new ApiRoute(RequestMethod.PUT, "/api/v1/platform-operators/accounts/{operatorId}/authority"),
                new ApiRoute(RequestMethod.PUT, "/api/v1/platform-operators/accounts/{operatorId}/suspension"),
                new ApiRoute(RequestMethod.GET, "/api/v1/platform-operators/audit-events"),
                new ApiRoute(RequestMethod.GET, "/api/v1/platform-operators/audit-events/{eventKey}"),
                new ApiRoute(RequestMethod.POST,
                        "/api/v1/platform-operators/audit-events/{eventKey}/corrections"));
    }

    @Test
    @DisplayName("consumer namespace는 consumer audience 패키지만 선언할 수 있다")
    void consumerNamespaceIsOwnedByConsumerAudiencePackage() {
        Set<ControllerRoute> routes = Set.of(new ControllerRoute(
                "com.miriyum.domain.search.controller.publicapi",
                new ApiRoute(RequestMethod.GET, "/api/v1/consumers/me/widgets")));

        assertThatThrownBy(() -> assertAudienceNamespaces(routes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("consumer namespace owner");
    }

    @Test
    @DisplayName("store-operator namespace는 storeoperator audience 패키지만 선언할 수 있다")
    void storeOperatorNamespaceIsOwnedByStoreOperatorAudiencePackage() {
        Set<ControllerRoute> routes = Set.of(new ControllerRoute(
                "com.miriyum.domain.search.controller.publicapi",
                new ApiRoute(RequestMethod.GET, "/api/v1/store-operators/stores")));

        assertThatThrownBy(() -> assertAudienceNamespaces(routes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("store-operator namespace owner");
    }

    @Test
    @DisplayName("platform-operator namespace는 platformoperator audience 패키지만 선언할 수 있다")
    void platformOperatorNamespaceIsOwnedByPlatformOperatorAudiencePackage() {
        Set<ControllerRoute> routes = Set.of(new ControllerRoute(
                "com.miriyum.domain.search.controller.publicapi",
                new ApiRoute(RequestMethod.GET, "/api/v1/platform-operators/audits")));

        assertThatThrownBy(() -> assertAudienceNamespaces(routes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("platform-operator namespace owner");
    }

    @Test
    void consumerLookalikePackageCannotOwnConsumerNamespace() {
        Set<ControllerRoute> routes = Set.of(new ControllerRoute(
                "com.miriyum.domain.search.controller.consumerproxy",
                new ApiRoute(RequestMethod.GET, "/api/v1/consumers/me/widgets")));

        assertThatThrownBy(() -> assertAudienceNamespaces(routes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("consumer namespace owner");
    }

    @Test
    void storeOperatorLookalikePackageCannotOwnStoreOperatorNamespace() {
        Set<ControllerRoute> routes = Set.of(new ControllerRoute(
                "com.miriyum.domain.search.controller.storeoperatorlegacy",
                new ApiRoute(RequestMethod.GET, "/api/v1/store-operators/stores")));

        assertThatThrownBy(() -> assertAudienceNamespaces(routes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("store-operator namespace owner");
    }

    private static void assertAudienceNamespaces(Set<ControllerRoute> routes) {
        assertThat(routes).isNotEmpty();
        assertThat(routes).noneMatch(route -> LEGACY_ROOTS.stream()
                .anyMatch(legacy -> route.route().path().equals(legacy)
                        || route.route().path().startsWith(legacy + "/")));
        assertThat(routes.stream().filter(HttpApiNamespaceContractTest::isConsumer))
                .as("consumer audience canonical root")
                .allMatch(route -> usesNamespace(route, CONSUMER_ROOT));
        assertThat(routes.stream().filter(HttpApiNamespaceContractTest::isStoreOperator))
                .as("store-operator audience canonical root")
                .allMatch(route -> usesNamespace(route, STORE_OPERATOR_ROOT));
        assertThat(routes.stream().filter(HttpApiNamespaceContractTest::isPlatformOperator))
                .as("platform-operator audience canonical root")
                .allMatch(route -> usesNamespace(route, PLATFORM_OPERATOR_ROOT));
        assertThat(routes.stream().filter(route -> usesNamespace(route, CONSUMER_ROOT)))
                .as("consumer namespace owner")
                .allMatch(HttpApiNamespaceContractTest::isConsumer);
        assertThat(routes.stream().filter(route -> usesNamespace(route, STORE_OPERATOR_ROOT)))
                .as("store-operator namespace owner")
                .allMatch(HttpApiNamespaceContractTest::isStoreOperator);
        assertThat(routes.stream().filter(route -> usesNamespace(route, PLATFORM_OPERATOR_ROOT)))
                .as("platform-operator namespace owner")
                .allMatch(HttpApiNamespaceContractTest::isPlatformOperator);
    }

    private static boolean usesNamespace(ControllerRoute route, String root) {
        String path = route.route().path();
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static boolean isConsumer(ControllerRoute route) {
        return isPackageOrChild(route.packageName(), "com.miriyum.domain.consumer.controller")
                || containsPackageOrChild(route.packageName(), ".controller.consumer");
    }

    private static boolean isStoreOperator(ControllerRoute route) {
        return isPackageOrChild(route.packageName(), "com.miriyum.domain.storeoperator.controller")
                || containsPackageOrChild(route.packageName(), ".controller.storeoperator");
    }

    private static boolean isPlatformOperator(ControllerRoute route) {
        return isPackageOrChild(route.packageName(), "com.miriyum.domain.platformoperator.controller");
    }

    private static boolean isPackageOrChild(String packageName, String packageRoot) {
        return packageName.equals(packageRoot) || packageName.startsWith(packageRoot + ".");
    }

    private static boolean containsPackageOrChild(String packageName, String packageSuffix) {
        return packageName.endsWith(packageSuffix) || packageName.contains(packageSuffix + ".");
    }
}
