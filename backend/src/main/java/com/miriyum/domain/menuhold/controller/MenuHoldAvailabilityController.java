package com.miriyum.domain.menuhold.controller;

import com.miriyum.domain.menuhold.controller.dto.MenuHoldAvailabilityRequest;
import com.miriyum.domain.menuhold.controller.dto.MenuHoldAvailabilityResponse;
import com.miriyum.domain.menuhold.service.MenuHoldAvailabilityQueryService;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@Validated
@RestController
@RequestMapping("/api/v1/stores/{storeId}/menu-hold-availability")
@RequiredArgsConstructor
public class MenuHoldAvailabilityController {

    private final MenuHoldAvailabilityQueryService queryService;

    @GetMapping
    public ApiResponse<MenuHoldAvailabilityResponse> getAvailability(
            @PathVariable @Positive long storeId,
            @Valid @ModelAttribute MenuHoldAvailabilityRequest request) {
        return ApiResponse.success("메뉴 예약 가능 수량을 조회했습니다.",
                queryService.findAvailability(storeId, request.serviceDate(),
                        request.startTime(), request.parsedStartOffset()));
    }
}
