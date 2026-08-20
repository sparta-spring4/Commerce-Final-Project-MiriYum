package com.miriyum.domain.menuhold.repository;

import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.menuhold.entity.MenuHold;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuHoldRepository extends JpaRepository<MenuHold, Long> {
    boolean existsByReservationId(long reservationId);
    boolean existsByAcquireOperationId(String acquireOperationId);

    Optional<MenuHold> findByReservationHoldId(Long reservationHoldId);
    Optional<MenuHold> findByReservationIdAndReservationHoldIdIsNull(long reservationId);
    List<MenuHold> findAllByReservationHoldIdIn(List<Long> reservationHoldIds);
    List<MenuHold> findAllByReservationIdInAndReservationHoldIdIsNull(List<Long> reservationIds);

    /** ReservationHold 종결 뒤 임시 MenuHold 루트 행만 비관적 잠금으로 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select hold
              from MenuHold hold
             where hold.reservationHoldId = :reservationHoldId
            """)
    Optional<MenuHold> findByReservationHoldIdForUpdate(
            @Param("reservationHoldId") Long reservationHoldId);

    /** 예약 종결 명령을 직렬화하기 위해 연결 홀드를 비관적 잠금으로 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select hold
              from MenuHold hold
             where hold.reservationId = :reservationId
            """)
    Optional<MenuHold> findByReservationIdForUpdate(
            @Param("reservationId") long reservationId);

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
