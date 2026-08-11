package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 예약 상세 조정자에게 저장된 메뉴 선택 거래 스냅샷을 제공한다. */
@Service
@RequiredArgsConstructor
public class MenuHoldSnapshotQueryService {

    private final MenuHoldRepository menuHoldRepository;

    /**
     * 예약의 메뉴 선택을 저장 순서대로 반환한다.
     *
     * @param reservationId 메뉴 선택을 조회할 예약의 내부 식별자
     * @return 현재 메뉴 상태와 무관한 예약 당시 메뉴 선택의 불변 목록
     */
    @Transactional(readOnly = true)
    public List<MenuHoldItemResult> findByReservationId(long reservationId) {
        return List.copyOf(
                menuHoldRepository.findItemSnapshotsByReservationId(reservationId));
    }
}
