package com.miriyum.domain.menu.service;

import com.miriyum.domain.menu.dto.contract.RepresentativeMenuItem;
import com.miriyum.domain.menu.dto.contract.RepresentativeMenuSnapshot;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import com.miriyum.domain.menu.repository.MenuRepository;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RepresentativeMenuQueryService {

    private final MenuRepository menuRepository;

    @Transactional(readOnly = true)
    public RepresentativeMenuSnapshot getCurrent(long storeId) {
        List<MenuRepository.RepresentativeMenuRow> rows =
                menuRepository.findRepresentativeMenuRows(storeId);
        if (rows.isEmpty()) {
            return RepresentativeMenuSnapshot.unconfigured(storeId);
        }
        MenuRepository.RepresentativeMenuRow setting = rows.getFirst();
        List<MenuRepository.RepresentativeMenuRow> eligibleRows = rows.stream()
                .filter(row -> row.getMenuId() != null && row.getName() != null)
                .toList();
        List<RepresentativeMenuItem> items = IntStream.range(0, eligibleRows.size())
                .mapToObj(index -> {
                    MenuRepository.RepresentativeMenuRow row = eligibleRows.get(index);
                    return new RepresentativeMenuItem(
                        String.valueOf(row.getMenuId()),
                        index + 1,
                        row.getPublishedVersionNumber(),
                        row.getName(),
                        row.getPrice(),
                        MenuSellingStatus.valueOf(row.getSellingStatus()));
                })
                .toList();
        return new RepresentativeMenuSnapshot(
                String.valueOf(setting.getStoreId()),
                setting.getVersion(),
                RepresentativeMenuSettingStatus.valueOf(setting.getStatus()),
                items);
    }
}
