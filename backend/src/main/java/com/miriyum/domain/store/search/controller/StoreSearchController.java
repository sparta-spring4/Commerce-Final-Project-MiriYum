package com.miriyum.domain.store.search.controller;

import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.search.dto.PublicMenuList;
import com.miriyum.domain.store.search.dto.PublicStoreDetail;
import com.miriyum.domain.store.search.dto.PublicStorePage;
import com.miriyum.domain.store.search.model.ReservationSearchCondition;
import com.miriyum.domain.store.search.model.StoreSearchQuery;
import com.miriyum.domain.store.search.service.StorePublicQueryService;
import com.miriyum.domain.store.search.service.StoreSearchCoreService;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/stores")
public class StoreSearchController {

    private final StoreSearchCoreService searchService;
    private final StorePublicQueryService publicQueryService;

    public StoreSearchController(
            StoreSearchCoreService searchService,
            StorePublicQueryService publicQueryService
    ) {
        this.searchService = searchService;
        this.publicQueryService = publicQueryService;
    }

    @GetMapping
    public ApiResponse<PublicStorePage> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Region region,
            @RequestParam(required = false) String storeCategoryCode,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
            @RequestParam(required = false) Integer partySize,
            @RequestParam(defaultValue = "false") boolean includesInfants,
            @RequestParam(defaultValue = "false") boolean availableOnly,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        StoreSearchQuery query = StoreSearchQuery.from(
                keyword, region, storeCategoryCode, serviceDate, startTime, partySize,
                availableOnly, sort, page, size);
        return ApiResponse.success(
                "매장을 조회했습니다.",
                PublicStorePage.from(searchService.search(query, includesInfants)));
    }

    @GetMapping("/{storeId}")
    public ApiResponse<PublicStoreDetail> detail(
            @PathVariable @Positive long storeId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
            @RequestParam(required = false) Integer partySize,
            @RequestParam(defaultValue = "false") boolean includesInfants
    ) {
        ReservationSearchCondition condition = StoreSearchQuery.from(
                null, null, null, serviceDate, startTime, partySize,
                false, null, 0, 20).reservationCondition();
        return ApiResponse.success(
                "매장을 조회했습니다.",
                publicQueryService.getDetail(storeId, condition, includesInfants));
    }

    @GetMapping("/{storeId}/menus")
    public ApiResponse<PublicMenuList> menus(@PathVariable @Positive long storeId) {
        return ApiResponse.success(
                "메뉴를 조회했습니다.",
                new PublicMenuList(publicQueryService.getMenus(storeId)));
    }
}
