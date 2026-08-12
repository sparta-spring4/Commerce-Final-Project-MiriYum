package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketView;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menu.dto.storeoperator.ManagedMenuResponse;
import com.miriyum.domain.menu.service.MenuQueryService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MenuInventoryAdminService {

    private final MenuQueryService menuQueryService;
    private final MenuInventoryBucketRepository bucketRepository;

    @Transactional(readOnly = true)
    public Page<InventoryBucketView> list(
            long operatorId,
            long storeId,
            LocalDate serviceDate,
            Long menuId,
            Pageable pageable
    ) {
        List<Long> menuIds;
        if (menuId == null) {
            menuIds = menuQueryService.list(operatorId, storeId).stream()
                    .map(ManagedMenuResponse::menuId)
                    .map(Long::parseLong)
                    .toList();
        } else {
            menuQueryService.get(operatorId, storeId, menuId);
            menuIds = List.of(menuId);
        }
        if (menuIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return bucketRepository.findCurrentPage(
                        menuIds, serviceDate, menuId, pageable)
                .map(InventoryBucketView::from);
    }
}
