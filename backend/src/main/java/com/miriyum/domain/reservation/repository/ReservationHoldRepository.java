package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationHold;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 임시 선점 루트의 최소 영속성 경계다. */
public interface ReservationHoldRepository extends JpaRepository<ReservationHold, Long> {

    /** 무잠금 만료 후보 조회가 반환하는 최소 projection이다. */
    interface ExpirationCandidate {

        Long getReservationHoldId();

        Instant getExpiresAt();
    }

    /**
     * 종결 명령을 직렬화하기 위해 선점 aggregate를 비관적 쓰기 잠금으로 조회한다.
     *
     * @param reservationHoldId 선점 식별자
     * @return 잠근 선점 또는 빈 결과
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select hold from ReservationHold hold where hold.id = :reservationHoldId")
    Optional<ReservationHold> findByIdForUpdate(
            @Param("reservationHoldId") Long reservationHoldId
    );

    Optional<ReservationHold> findByConsumerAccountIdAndCreationCommandId(
            Long consumerAccountId,
            String creationCommandId
    );

    /**
     * 고정 cutoff까지 만료된 ACTIVE 선점을 PK keyset 순서로 조회한다.
     *
     * <p>이 결과는 잠금 없는 힌트이며 실제 만료 여부는 종결 transaction에서 다시 판정한다.</p>
     *
     * @param cutoff 한 poll이 공유하는 중앙 시각
     * @param afterId 직전 페이지의 마지막 선점 ID
     * @param pageable 최대 batch 크기
     * @return ID와 영속 만료 시각 projection
     */
    @Query("""
            select hold.id as reservationHoldId, hold.expiresAt as expiresAt
            from ReservationHold hold
            where hold.status =
                :#{T(com.miriyum.domain.reservation.entity.ReservationHoldStatus).ACTIVE}
              and hold.expiresAt <= :cutoff
              and hold.id > :afterId
            order by hold.id asc
            """)
    List<ExpirationCandidate> findActiveExpirationCandidatesAfter(
            @Param("cutoff") Instant cutoff,
            @Param("afterId") long afterId,
            Pageable pageable
    );

    /**
     * 같은 소비자·매장의 서비스 구간과 겹치는 보호 상태 선점을 PK 순서로 잠근다.
     *
     * @param consumerAccountId 인증된 소비자 계정 식별자
     * @param storeId 대상 매장 식별자
     * @param requestedStart 요청 서비스 시작 시각(포함)
     * @param requestedEnd 요청 서비스 종료 시각(제외)
     * @return ACTIVE, RECONCILIATION_REQUIRED, CONFIRMED 선점의 PK 오름차순 목록
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select hold
            from ReservationHold hold
            where hold.consumerAccountId = :consumerAccountId
              and hold.storeId = :storeId
              and hold.status in (
                  :#{T(com.miriyum.domain.reservation.entity.ReservationHoldStatus).ACTIVE},
                  :#{T(com.miriyum.domain.reservation.entity.ReservationHoldStatus).RECONCILIATION_REQUIRED},
                  :#{T(com.miriyum.domain.reservation.entity.ReservationHoldStatus).CONFIRMED}
              )
              and hold.timeSnapshot.startAt < :requestedEnd
              and hold.timeSnapshot.serviceEndAt > :requestedStart
            order by hold.id asc
            """)
    List<ReservationHold> findProtectedOverlappingForUpdate(
            @Param("consumerAccountId") long consumerAccountId,
            @Param("storeId") long storeId,
            @Param("requestedStart") Instant requestedStart,
            @Param("requestedEnd") Instant requestedEnd
    );

    /**
     * 수용량 정책 재게시가 이월할 보호 상태 선점을 PK 순서로 잠근다.
     *
     * @param storeId 대상 매장 식별자
     * @param serviceDate 매장 현지 업무 날짜
     * @return ACTIVE, RECONCILIATION_REQUIRED, CONFIRMED 선점의 PK 오름차순 목록
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select hold
            from ReservationHold hold
            where hold.storeId = :storeId
              and hold.timeSnapshot.serviceDate = :serviceDate
              and hold.status in (
                  :#{T(com.miriyum.domain.reservation.entity.ReservationHoldStatus).ACTIVE},
                  :#{T(com.miriyum.domain.reservation.entity.ReservationHoldStatus).RECONCILIATION_REQUIRED},
                  :#{T(com.miriyum.domain.reservation.entity.ReservationHoldStatus).CONFIRMED}
              )
            order by hold.id asc
            """)
    List<ReservationHold> findProtectedByStoreIdAndServiceDateForUpdateOrderByIdAsc(
            @Param("storeId") long storeId,
            @Param("serviceDate") LocalDate serviceDate
    );
}
