package com.miriyum.domain.menu.service;

import com.miriyum.domain.menu.dto.contract.RepresentativeMenuItem;
import com.miriyum.domain.menu.dto.contract.RepresentativeMenuSnapshot;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuItemResponse;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.entity.RepresentativeMenuSetting;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVersionStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.menu.repository.RepresentativeMenuSettingRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RepresentativeMenuQueryService {

    private final RepresentativeMenuSettingRepository settingRepository;
    private final MenuRepository menuRepository;

    @Transactional(readOnly = true)
    public RepresentativeMenuSnapshot getCurrent(long storeId) {
        return settingRepository.findDetailedByStoreId(storeId)
                .map(this::snapshot)
                .orElseGet(RepresentativeMenuSnapshot::unconfigured);
    }

    private RepresentativeMenuSnapshot snapshot(RepresentativeMenuSetting setting) {
        List<Long> orderedIds = setting.orderedMenuIds();
        Map<Long, Menu> byId = new HashMap<>();
        menuRepository.findAllManagedByIds(orderedIds)
                .forEach(menu -> byId.put(menu.getId(), menu));
        List<RepresentativeMenuItem> items = new ArrayList<>();
        for (int index = 0; index < orderedIds.size(); index++) {
            Menu menu = byId.get(orderedIds.get(index));
            if (menu == null || !isEligible(menu)) {
                continue;
            }
            RepresentativeMenuItemResponse response =
                    RepresentativeMenuItemResponse.from(menu, index + 1);
            items.add(new RepresentativeMenuItem(
                    response.menuId(), response.displayOrder(),
                    response.publishedVersionNumber(), response.name(),
                    response.price(), response.sellingStatus()));
        }
        return new RepresentativeMenuSnapshot(
                setting.getVersion(), setting.getStatus(), items);
    }

    private static boolean isEligible(Menu menu) {
        return !menu.isRetired()
                && menu.getPublishedVersionNumber() != null
                && menu.getVersions().stream()
                        .anyMatch(version -> version.getVersionNumber()
                                == menu.getPublishedVersionNumber()
                                && version.getStatus() == MenuVersionStatus.PUBLISHED)
                && menu.getVisibility() == MenuVisibility.VISIBLE
                && (menu.getSellingStatus() == MenuSellingStatus.SELLING
                || menu.getSellingStatus() == MenuSellingStatus.SOLD_OUT);
    }
}
