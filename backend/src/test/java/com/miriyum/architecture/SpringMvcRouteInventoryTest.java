package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class SpringMvcRouteInventoryTest {

    @Test
    void combinesControllerAndMethodMappingsWithoutChangingPathVariableNames() {
        assertThat(SpringMvcRouteInventory.routesFor(ReservationCancellationController.class))
                .extracting(ControllerRoute::route)
                .containsExactly(new ApiRoute(
                        org.springframework.web.bind.annotation.RequestMethod.POST,
                        "/api/v1/consumers/me/reservations/{reservationId}/cancellations"));
    }

    @Test
    void rejectsMappingsThatDoNotDeclareAnHttpMethod() {
        assertThatThrownBy(() -> SpringMvcRouteInventory.routesFor(AmbiguousController.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit HTTP method")
                .hasMessageContaining("AmbiguousController");
    }

    @Test
    void includesInheritedMappedHandlerMethods() {
        assertThat(SpringMvcRouteInventory.routesFor(InheritedController.class))
                .extracting(ControllerRoute::route)
                .containsExactly(new ApiRoute(
                        org.springframework.web.bind.annotation.RequestMethod.POST,
                        "/api/v1/stores/{storeId}/alternative-searches"));
    }

    @Test
    void excludesNestedTestFixturesFromProductionInventory() {
        assertThat(SpringMvcRouteInventory.routes())
                .extracting(route -> route.route().path())
                .doesNotContain("/api/v1/platform-operators/test-business");
    }

    @RestController
    @RequestMapping("/api/v1/consumers/me")
    private static class ReservationCancellationController {

        @PostMapping("/reservations/{reservationId}/cancellations")
        HttpStatus cancel() {
            return HttpStatus.NO_CONTENT;
        }
    }

    @RestController
    @RequestMapping("/api/v1/consumers/me")
    private static class AmbiguousController {

        @RequestMapping("/reservations")
        HttpStatus ambiguous() {
            return HttpStatus.OK;
        }
    }

    private abstract static class BaseController {

        @PostMapping("/{storeId}/alternative-searches")
        HttpStatus search() {
            return HttpStatus.OK;
        }
    }

    @RestController
    @RequestMapping("/api/v1/stores")
    private static class InheritedController extends BaseController {
    }
}
