package com.miriyum.domain.menuhold.repository;

import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.menuhold.entity.MenuHold;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuHoldRepository extends JpaRepository<MenuHold, Long> {
    boolean existsByReservationId(long reservationId);
    boolean existsByAcquireOperationId(String acquireOperationId);

    /**
     * 예약 당시 메뉴 표시값과 수량만 항목 생성 순서로 projection한다.
     *
     * @param reservationId 메뉴 홀드와 연결된 예약의 내부 식별자
     * @return MenuHold 내부 상태와 식별자를 포함하지 않는 거래 스냅샷 목록
     */
    @Query("""
            select new com.miriyum.domain.menuhold.dto.MenuHoldItemResult(
                    item.menuId,
                    item.menuNameSnapshot,
                    cast(item.unitPriceSnapshot as long),
                    item.quantity)
              from MenuHoldItem item
             where item.menuHold.reservationId = :reservationId
             order by item.id asc
            """)
    List<MenuHoldItemResult> findItemSnapshotsByReservationId(
            @Param("reservationId") long reservationId);
}
