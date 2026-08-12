package com.miriyum.domain.pickup.controller.publicapi;

import com.miriyum.domain.pickup.dto.response.PickupAvailability;
import com.miriyum.domain.pickup.service.PickupAvailabilityService;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/stores/{storeId}/pickup-availability")
public class PickupAvailabilityController {

    private final PickupAvailabilityService pickupAvailabilityService;

    public PickupAvailabilityController(PickupAvailabilityService pickupAvailabilityService) {
        this.pickupAvailabilityService = pickupAvailabilityService;
    }

    @GetMapping
    public ApiResponse<PickupAvailability> getAvailability(
            @PathVariable @Positive long storeId,
            @RequestParam
            @NotNull
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate pickupDate
    ) {
        return ApiResponse.success(
                "픽업 가능 수량을 조회했습니다.",
                pickupAvailabilityService.getAvailability(storeId, pickupDate)
        );
    }
}
