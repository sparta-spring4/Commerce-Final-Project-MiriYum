package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HttpApiNamespaceContractTest {

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

        assertThat(routes).isNotEmpty();
        assertThat(routes).noneMatch(route -> LEGACY_ROOTS.stream()
                .anyMatch(legacy -> route.route().path().equals(legacy)
                        || route.route().path().startsWith(legacy + "/")));
        assertThat(routes.stream().filter(HttpApiNamespaceContractTest::isConsumer))
                .allMatch(route -> route.route().path().startsWith("/api/v1/consumers"));
        assertThat(routes.stream().filter(HttpApiNamespaceContractTest::isStoreOperator))
                .allMatch(route -> route.route().path().startsWith("/api/v1/store-operators"));
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
