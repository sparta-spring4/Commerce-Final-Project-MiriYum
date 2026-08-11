package com.miriyum.domain.menu.service;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.menu.dto.contract.MenuHoldSelectableMenu;
import com.miriyum.domain.menu.repository.MenuHoldSelectionRow;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MenuHold 소비자에게 Store 소유의 현재 메뉴 홀드 선택 후보를 제공한다. */
@Service
public class MenuHoldSelectionQueryService {

    private final MenuRepository menuRepository;

    MenuHoldSelectionQueryService(MenuRepository menuRepository) {
        this.menuRepository = menuRepository;
    }

    /**
     * 매장의 현재 메뉴 홀드 선택 후보를 잠금 없이 한 번에 조회한다.
     *
     * @param storeId 대상 매장 식별자
     * @return 메뉴 식별자 오름차순의 현재 게시 메뉴 스냅샷
     * @throws ServiceException 매장이 존재하지 않는 경우
     */
    @Transactional(readOnly = true)
    public List<MenuHoldSelectableMenu> findSelectableMenus(long storeId) {
        List<MenuHoldSelectionRow> rows =
                menuRepository.findMenuHoldSelectionRows(storeId);
        if (rows.isEmpty()) {
            throw new ServiceException(StoreErrorCode.STORE_NOT_FOUND);
        }
        return rows.stream()
                .filter(row -> row.getMenuId() != null)
                .map(row -> new MenuHoldSelectableMenu(
                        row.getMenuId(), row.getMenuName(), row.getUnitPrice()))
                .sorted(Comparator.comparingLong(MenuHoldSelectableMenu::menuId))
                .toList();
    }
}
