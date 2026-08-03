package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reservation 시간 정책 버전의 영속성과 현재 효력 일괄 조회를 소유한다.
 */
public interface ReservationTimePolicyVersionRepository
        extends JpaRepository<ReservationTimePolicyVersion, Long> {

    /**
     * 요청한 매장들의 지정 시각 현재 활성 정책을 단일 조회로 반환한다.
     *
     * @param storeIds 중복이 제거된 매장 ID 집합
     * @param status 조회할 활성 상태
     * @param effectiveAt 중앙 효력 판정 시각
     * @return 효력 시각을 넘지 않은 활성 정책 목록
     */
    @Query("""
            select policy
            from ReservationTimePolicyVersion policy
            where policy.storeId in :storeIds
              and policy.status = :status
              and policy.effectiveAt <= :effectiveAt
            order by policy.storeId asc
            """)
    List<ReservationTimePolicyVersion> findEffectiveActiveByStoreIds(
            @Param("storeIds") Collection<Long> storeIds,
            @Param("status") ReservationTimePolicyStatus status,
            @Param("effectiveAt") Instant effectiveAt
    );
}
