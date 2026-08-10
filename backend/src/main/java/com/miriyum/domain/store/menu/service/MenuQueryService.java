package com.miriyum.domain.store.menu.service;

import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.dto.ManagedMenuResponse;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MenuQueryService {

    private final StoreService storeService;
    private final MenuRepository menuRepository;

    @Transactional(readOnly = true)
    public List<ManagedMenuResponse> list(long operatorId, long storeId) {
        storeService.requireManagementOwnership(operatorId, storeId);
        return menuRepository.findAllManagedByStoreId(storeId).stream()
                .map(ManagedMenuResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ManagedMenuResponse get(long operatorId, long storeId, long menuId) {
        storeService.requireManagementOwnership(operatorId, storeId);
        return ManagedMenuResponse.from(loadForStore(menuRepository.findManagedById(menuId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.MENU_NOT_FOUND)), storeId));
    }

    static Menu loadForStore(Menu menu, long storeId) {
        if (menu.getStoreId() != storeId) {
            throw new ServiceException(StoreErrorCode.MENU_NOT_FOUND);
        }
        return menu;
    }
}
