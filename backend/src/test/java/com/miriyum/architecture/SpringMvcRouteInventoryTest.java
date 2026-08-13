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
}
