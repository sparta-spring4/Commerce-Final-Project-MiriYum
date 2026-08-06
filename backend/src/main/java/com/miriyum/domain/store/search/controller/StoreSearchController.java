package com.miriyum.domain.store.search.controller;

import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.search.dto.PublicMenuList;
import com.miriyum.domain.store.search.dto.PublicStoreDetail;
import com.miriyum.domain.store.search.dto.PublicStorePage;
import com.miriyum.domain.store.search.model.ReservationSearchCondition;
import com.miriyum.domain.store.search.model.StoreSearchQuery;
import com.miriyum.domain.store.search.service.StorePublicQueryService;
import com.miriyum.domain.store.search.service.StoreSearchCoreService;
import com.miriyum.domain.store.search.service.IntegratedStoreSearchService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.ApiResponse;
import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final IntegratedStoreSearchService integratedSearchService;
    private final StorePublicQueryService publicQueryService;

    public StoreSearchController(
            StoreSearchCoreService searchService,
            IntegratedStoreSearchService integratedSearchService,
            StorePublicQueryService publicQueryService
    ) {
        this.searchService = searchService;
        this.integratedSearchService = integratedSearchService;
        this.publicQueryService = publicQueryService;
    }

    @GetMapping
    public ApiResponse<?> search(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false)
            @Size(min = 1, max = 100)
            @Pattern(regexp = ".*\\S.*") String searchInput,
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
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false)
            @Size(max = 1024) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (searchInput != null) {
            requireNoLegacyParameters(
                    keyword, region, storeCategoryCode,
                    serviceDate, startTime, partySize, page);
            return ApiResponse.success(
                    "매장을 조회했습니다.",
                    integratedSearchService.search(
                            principal == null ? null : principal.accountId(),
                            searchInput,
                            includesInfants,
                            availableOnly,
                            sort,
                            cursor,
                            size));
        }
        if (cursor != null) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        StoreSearchQuery query = StoreSearchQuery.from(
                keyword, region, storeCategoryCode, serviceDate, startTime, partySize,
                availableOnly, sort, page == null ? 0 : page, size);
        return ApiResponse.success(
                "매장을 조회했습니다.",
                PublicStorePage.from(searchService.search(query, includesInfants)));
    }

    private static void requireNoLegacyParameters(
            String keyword,
            Region region,
            String storeCategoryCode,
            LocalDate serviceDate,
            LocalTime startTime,
            Integer partySize,
            Integer page
    ) {
        if (keyword != null
                || region != null
                || storeCategoryCode != null
                || serviceDate != null
                || startTime != null
                || partySize != null
                || page != null) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
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
        ReservationSearchCondition condition = ReservationSearchCondition.fromNullable(
                serviceDate, startTime, partySize);
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
