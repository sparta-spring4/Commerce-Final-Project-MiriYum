package com.miriyum.domain.menu.service;

import com.miriyum.domain.menu.dto.contract.MenuTransactionEligibility;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.entity.MenuVersion;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.store.dto.contract.StoreMenuTransactionEligibility;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.global.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신규 메뉴 홀드·픽업 거래를 위해 Store와 Menu를 정해진 순서로 잠금 검증한다.
 */
@Service
public class MenuTransactionService {

    private final StoreTransactionEligibilityService storeEligibilityService;
    private final MenuRepository menuRepository;

    public MenuTransactionService(
            StoreTransactionEligibilityService storeEligibilityService,
            MenuRepository menuRepository
    ) {
        this.storeEligibilityService = storeEligibilityService;
        this.menuRepository = menuRepository;
    }

    /**
     * 호출자가 시작한 거래에서 Store를 먼저, Menu를 다음으로 잠그고 거래 스냅샷을 만든다.
     *
     * @param storeId 대상 매장 식별자
     * @param menuId 대상 메뉴 식별자
     * @return 현재 게시 메뉴 버전을 포함한 거래 자격
     * @throws ServiceException 매장·메뉴가 없거나 신규 거래가 불가능한 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MenuTransactionEligibility requireTransactionEligibility(
            long storeId,
            long menuId
    ) {
        StoreMenuTransactionEligibility store =
                storeEligibilityService.requireMenuTransactionEligibility(storeId);
        Menu menu = menuRepository.findByIdForUpdate(menuId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.MENU_NOT_FOUND));
        if (menu.getStoreId() != store.storeId()) {
            throw new ServiceException(StoreErrorCode.MENU_NOT_FOUND);
        }

        MenuVersion published = menu.requireTransactionVersion();
        boolean menuHoldEligible = store.reservationEnabled()
                && store.menuHoldEnabled()
                && published.isHoldSelectionAllowed();
        boolean pickupEligible = store.pickupEnabled()
                && published.isPickupSelectionAllowed();
        return new MenuTransactionEligibility(
                storeId,
                menuId,
                published.getVersionNumber(),
                published.getName(),
                published.getPrice(),
                menuHoldEligible,
                pickupEligible);
    }
}
