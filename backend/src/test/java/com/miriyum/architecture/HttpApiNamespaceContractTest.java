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
        assertThat(routes.stream().filter(route -> usesNamespace(route, CONSUMER_ROOT)))
                .as("consumer namespace owner")
                .allMatch(HttpApiNamespaceContractTest::isConsumer);
        assertThat(routes.stream().filter(route -> usesNamespace(route, STORE_OPERATOR_ROOT)))
                .as("store-operator namespace owner")
                .allMatch(HttpApiNamespaceContractTest::isStoreOperator);
    }

    private static boolean usesNamespace(ControllerRoute route, String root) {
        String path = route.route().path();
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static boolean isConsumer(ControllerRoute route) {
        return route.packageName().startsWith("com.miriyum.domain.consumer.controller.")
                || route.packageName().contains(".controller.consumer");
    }

    private static boolean isStoreOperator(ControllerRoute route) {
        return route.packageName().startsWith("com.miriyum.domain.storeoperator.controller.")
                || route.packageName().contains(".controller.storeoperator");
    }
}
