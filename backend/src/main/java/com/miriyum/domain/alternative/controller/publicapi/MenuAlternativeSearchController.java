package com.miriyum.domain.alternative.controller.publicapi;

import com.miriyum.domain.alternative.dto.publicapi.MenuAlternativeSearchRequest;
import com.miriyum.domain.alternative.dto.publicapi.MenuAlternativeSearchResponse;
import com.miriyum.domain.alternative.service.MenuAlternativeSearchService;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/stores/{storeId}/menus/{menuId}")
public class MenuAlternativeSearchController {
    private final MenuAlternativeSearchService service;

    public MenuAlternativeSearchController(MenuAlternativeSearchService service) {
        this.service = service;
    }

    @PostMapping("/alternative-searches")
    public ApiResponse<MenuAlternativeSearchResponse> search(
            @PathVariable @Positive long storeId, @PathVariable @Positive long menuId,
            @Valid @RequestBody MenuAlternativeSearchRequest request) {
        return ApiResponse.success("메뉴 대안을 조회했습니다.",
                MenuAlternativeSearchResponse.from(service.search(
                        storeId, menuId, request.toCommand())));
    }
}
