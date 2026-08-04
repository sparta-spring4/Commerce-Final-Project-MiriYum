package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reservation 시간 정책 버전의 영속성과 현재 효력 일괄 조회를 소유한다.
 */
public interface ReservationTimePolicyVersionRepository
        extends JpaRepository<ReservationTimePolicyVersion, Long> {

    @Query("""
            select coalesce(max(policy.versionNumber), 0)
            from ReservationTimePolicyVersion policy
            where policy.storeId = :storeId
            """)
    long findMaxVersionNumberByStoreId(@Param("storeId") long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select policy
            from ReservationTimePolicyVersion policy
            where policy.storeId = :storeId
              and policy.versionNumber = :versionNumber
            """)
    Optional<ReservationTimePolicyVersion> findByStoreIdAndVersionNumberForUpdate(
            @Param("storeId") long storeId,
            @Param("versionNumber") long versionNumber
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select policy
            from ReservationTimePolicyVersion policy
            where policy.storeId = :storeId
              and policy.status = :status
            """)
    Optional<ReservationTimePolicyVersion> findByStoreIdAndStatusForUpdate(
            @Param("storeId") long storeId,
            @Param("status") ReservationTimePolicyStatus status
    );

    @Query("""
            select policy.storeId
            from ReservationTimePolicyVersion policy
            where policy.id = :policyId
            """)
    Optional<Long> findStoreIdById(@Param("policyId") long policyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select policy
            from ReservationTimePolicyVersion policy
            where policy.id = :policyId
            """)
    Optional<ReservationTimePolicyVersion> findByIdForUpdate(
            @Param("policyId") long policyId
    );

    @Query("""
            select policy.id
            from ReservationTimePolicyVersion policy
            where policy.status = :status
              and policy.effectiveAt <= :now
            order by policy.effectiveAt asc, policy.id asc
            """)
    List<Long> findDueScheduledIds(
            @Param("status") ReservationTimePolicyStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );

    /**
     * 현재 ACTIVE와 효력 시각에 도달한 미전환 SCHEDULED를 한 번에 반환한다.
     * due SCHEDULED가 함께 나오면 소비자는 기존 ACTIVE를 사용하지 않고 실패 폐쇄한다.
     */
    @Query("""
            select policy
            from ReservationTimePolicyVersion policy
            where policy.storeId in :storeIds
              and policy.effectiveAt <= :evaluatedAt
              and (
                    policy.status = :activeStatus
                    or policy.status = :scheduledStatus
              )
            order by policy.storeId asc, policy.versionNumber asc
            """)
    List<ReservationTimePolicyVersion> findResolutionCandidatesByStoreIds(
            @Param("storeIds") Collection<Long> storeIds,
            @Param("activeStatus") ReservationTimePolicyStatus activeStatus,
            @Param("scheduledStatus") ReservationTimePolicyStatus scheduledStatus,
            @Param("evaluatedAt") Instant evaluatedAt
    );
}
