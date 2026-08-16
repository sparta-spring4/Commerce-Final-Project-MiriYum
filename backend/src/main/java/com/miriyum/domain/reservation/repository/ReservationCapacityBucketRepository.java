package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 예약 수용량 버킷의 기본 영속성 경계다.
 */
public interface ReservationCapacityBucketRepository
        extends JpaRepository<ReservationCapacityBucket, Long> {

    /**
     * Observes the newest materialized capacity policy version for one store business date.
     *
     * @param storeId target store ID
     * @param serviceDate store-local business date
     * @return the latest version, or empty when no materialized bucket exists
     */
    @Query("""
            select max(bucket.policyVersion)
            from ReservationCapacityBucket bucket
            where bucket.storeId = :storeId and bucket.serviceDate = :serviceDate
            """)
    Optional<Long> findLatestPolicyVersion(
            @Param("storeId") long storeId,
            @Param("serviceDate") LocalDate serviceDate
    );

    /**
     * Acquires one pessimistic write lock for the complete requested bucket union in PK order.
     *
     * @param bucketIds complete deduplicated union of original and current policy bucket IDs
     * @return locked bucket rows in ascending primary-key order
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select bucket from ReservationCapacityBucket bucket
            where bucket.id in :bucketIds
            order by bucket.id asc
            """)
    List<ReservationCapacityBucket> findAllByIdInForUpdate(
            @Param("bucketIds") Collection<Long> bucketIds
    );

    /**
     * Locks only the newest policy's buckets that overlap the requested half-open occupancy
     * interval. Callers retain responsibility for coverage, version consistency, and capacity
     * acceptance after the ordered lock acquisition.
     *
     * @param storeId target store ID
     * @param serviceDate store-local service date
     * @param startTime inclusive occupancy start
     * @param occupancyEndTime exclusive occupancy end
     * @return newest-policy overlapping buckets in primary-key order
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select bucket
            from ReservationCapacityBucket bucket
            where bucket.storeId = :storeId
              and bucket.serviceDate = :serviceDate
              and bucket.startTime < :occupancyEndTime
              and bucket.endTime > :startTime
              and bucket.policyVersion = (
                  select max(latest.policyVersion)
                  from ReservationCapacityBucket latest
                  where latest.storeId = bucket.storeId
                    and latest.serviceDate = bucket.serviceDate
              )
            order by bucket.id asc
            """)
    List<ReservationCapacityBucket> findLatestPolicyBucketsOverlappingForUpdate(
            @Param("storeId") long storeId,
            @Param("serviceDate") LocalDate serviceDate,
            @Param("startTime") LocalTime startTime,
            @Param("occupancyEndTime") LocalTime occupancyEndTime
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select bucket
            from ReservationCapacityBucket bucket
            where bucket.storeId = :storeId
              and bucket.serviceDate = :serviceDate
              and bucket.policyVersion = (
                  select max(latest.policyVersion)
                  from ReservationCapacityBucket latest
                  where latest.storeId = bucket.storeId
                    and latest.serviceDate = bucket.serviceDate
              )
            order by bucket.id asc
            """)
    List<ReservationCapacityBucket> findLatestPolicyBucketsForUpdate(
            @Param("storeId") long storeId,
            @Param("serviceDate") LocalDate serviceDate
    );

    /** 여러 매장의 업무 날짜별 최신 수용량 정책 전체를 한 번에 조회한다. */
    @Query("""
            select bucket
            from ReservationCapacityBucket bucket
            where bucket.storeId in :storeIds
              and bucket.serviceDate = :serviceDate
              and bucket.policyVersion = (
                  select max(latest.policyVersion)
                  from ReservationCapacityBucket latest
                  where latest.storeId = bucket.storeId
                    and latest.serviceDate = bucket.serviceDate
              )
            order by bucket.storeId asc,
                     bucket.startTime asc,
                     bucket.endTime asc,
                     bucket.id asc
            """)
    List<ReservationCapacityBucket> findLatestPolicyBuckets(
            @Param("storeIds") Collection<Long> storeIds,
            @Param("serviceDate") LocalDate serviceDate
    );

    /**
     * 여러 매장의 업무 날짜별 최신 정책에서 요청 구간과 겹치는 버킷을 한 번에 조회한다.
     *
     * @param storeIds 판정 대상 매장 ID 목록
     * @param serviceDate 매장 업무 날짜
     * @param startTime 요청 점유 시작 시각
     * @param queryEndTime 후보 중 가장 늦은 점유 종료 조회 상한. 최종 판정은 매장별 종료를 사용한다.
     * @return 매장·구간 순서로 정렬된 최신 정책 버킷
     */
    @Query("""
            select bucket
            from ReservationCapacityBucket bucket
            where bucket.storeId in :storeIds
              and bucket.serviceDate = :serviceDate
              and bucket.startTime < :queryEndTime
              and bucket.endTime > :startTime
              and bucket.policyVersion = (
                  select max(latest.policyVersion)
                  from ReservationCapacityBucket latest
                  where latest.storeId = bucket.storeId
                    and latest.serviceDate = bucket.serviceDate
              )
            order by bucket.storeId asc,
                     bucket.startTime asc,
                     bucket.endTime asc,
                     bucket.id asc
            """)
    List<ReservationCapacityBucket> findLatestPolicyBucketsOverlapping(
            @Param("storeIds") Collection<Long> storeIds,
            @Param("serviceDate") LocalDate serviceDate,
            @Param("startTime") LocalTime startTime,
            @Param("queryEndTime") LocalTime queryEndTime
    );

    /**
     * Observes only scalar IDs for the latest materialized cancellation candidates so the
     * subsequent pessimistic union query is the first operation to hydrate mutable entities.
     *
     * @param storeIds target store IDs
     * @param serviceDate store-local service date
     * @param startTime inclusive occupancy start
     * @param queryEndTime exclusive occupancy end
     * @return matching bucket IDs in ascending primary-key order
     */
    @Query("""
            select bucket.id
            from ReservationCapacityBucket bucket
            where bucket.storeId in :storeIds
              and bucket.serviceDate = :serviceDate
              and bucket.startTime < :queryEndTime
              and bucket.endTime > :startTime
              and bucket.policyVersion = (
                  select max(latest.policyVersion)
                  from ReservationCapacityBucket latest
                  where latest.storeId = bucket.storeId
                    and latest.serviceDate = bucket.serviceDate
              )
            order by bucket.id asc
            """)
    List<Long> findLatestPolicyBucketIdsOverlapping(
            @Param("storeIds") Collection<Long> storeIds,
            @Param("serviceDate") LocalDate serviceDate,
            @Param("startTime") LocalTime startTime,
            @Param("queryEndTime") LocalTime queryEndTime
    );

    @Query(value = """
            SELECT
                COALESCE(SUM(b.max_people), 0) AS offeredPeopleUnits,
                COALESCE(SUM(b.max_teams), 0) AS offeredTeamUnits,
                COALESCE(MAX(b.policy_version), 0) AS policyVersion,
                COALESCE(MAX(b.reservation_capacity_bucket_id), 0) AS maxBucketId
            FROM reservation_capacity_buckets b
            WHERE b.store_id = :storeId
              AND b.service_date = :businessDate
              AND b.policy_version = (
                  SELECT MAX(latest.policy_version)
                  FROM reservation_capacity_buckets latest
                  WHERE latest.store_id = :storeId
                    AND latest.service_date = :businessDate
              )
            """, nativeQuery = true)
    ReservationCapacityOfferAnalytics aggregateDashboardOffers(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate
    );

    interface ReservationCapacityOfferAnalytics {
        Long getOfferedPeopleUnits();
        Long getOfferedTeamUnits();
        Long getPolicyVersion();
        Long getMaxBucketId();
    }
}
