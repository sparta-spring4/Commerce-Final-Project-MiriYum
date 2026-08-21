package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 웨이팅 팀 잠금, FIFO 선두와 활성 수·종결 대상 조회를 소유한다. */
public interface WaitingTeamRepository extends JpaRepository<WaitingTeam, Long> {

    List<WaitingTeam> findAllByIdIn(List<Long> waitingTeamIds);

    /** 소비자·상태 묶음과 최신순 복합 cursor에 한정된 이력 page를 조회한다. */
    default List<WaitingTeam> findConsumerHistoryPage(
            long consumerAccountId,
            Collection<WaitingTeamStatus> statuses,
            Instant beforeCreatedAt,
            Long beforeWaitingTeamId,
            int limit
    ) {
        if (consumerAccountId <= 0 || statuses == null || statuses.isEmpty()
                || (beforeCreatedAt == null) != (beforeWaitingTeamId == null)
                || limit < 1) {
            throw new IllegalArgumentException("history scope, cursor and limit must be valid");
        }
        return findConsumerHistoryPage(
                consumerAccountId,
                statuses,
                beforeCreatedAt,
                beforeWaitingTeamId,
                PageRequest.of(0, limit));
    }

    @Query("""
            select team
            from WaitingTeam team
            where team.consumerAccountId = :consumerAccountId
              and team.status in :statuses
              and (:beforeCreatedAt is null
                   or team.createdAt < :beforeCreatedAt
                   or (team.createdAt = :beforeCreatedAt
                       and team.id < :beforeWaitingTeamId))
            order by team.createdAt desc, team.id desc
            """)
    List<WaitingTeam> findConsumerHistoryPage(
            @Param("consumerAccountId") long consumerAccountId,
            @Param("statuses") Collection<WaitingTeamStatus> statuses,
            @Param("beforeCreatedAt") Instant beforeCreatedAt,
            @Param("beforeWaitingTeamId") Long beforeWaitingTeamId,
            Pageable pageable
    );

    /** 매장·선택 상태·복합 cursor에 한정된 안정적인 FIFO 페이지를 조회한다. */
    default List<WaitingTeam> findKeysetPage(
            long storeId,
            WaitingTeamStatus status,
            Long afterQueueSequence,
            Long afterWaitingTeamId,
            int limit
    ) {
        if ((afterQueueSequence == null) != (afterWaitingTeamId == null)
                || limit < 1) {
            throw new IllegalArgumentException("cursor and limit must be valid");
        }
        long sequence = afterQueueSequence == null ? 0L : afterQueueSequence;
        long teamId = afterWaitingTeamId == null ? 0L : afterWaitingTeamId;
        Pageable page = PageRequest.of(0, limit);
        return status == null
                ? findKeysetPageWithoutStatus(storeId, sequence, teamId, page)
                : findKeysetPageWithStatus(storeId, status, sequence, teamId, page);
    }

    @Query("""
            select team
            from WaitingTeam team
            where team.storeId = :storeId
              and (team.queueSequence > :afterQueueSequence
                   or (team.queueSequence = :afterQueueSequence
                       and team.id > :afterWaitingTeamId))
            order by team.queueSequence asc, team.id asc
            """)
    List<WaitingTeam> findKeysetPageWithoutStatus(
            @Param("storeId") long storeId,
            @Param("afterQueueSequence") long afterQueueSequence,
            @Param("afterWaitingTeamId") long afterWaitingTeamId,
            Pageable pageable
    );

    @Query("""
            select team
            from WaitingTeam team
            where team.storeId = :storeId
              and team.status = :status
              and (team.queueSequence > :afterQueueSequence
                   or (team.queueSequence = :afterQueueSequence
                       and team.id > :afterWaitingTeamId))
            order by team.queueSequence asc, team.id asc
            """)
    List<WaitingTeam> findKeysetPageWithStatus(
            @Param("storeId") long storeId,
            @Param("status") WaitingTeamStatus status,
            @Param("afterQueueSequence") long afterQueueSequence,
            @Param("afterWaitingTeamId") long afterWaitingTeamId,
            Pageable pageable
    );

    Optional<WaitingTeam> findByIdAndStoreId(long waitingTeamId, long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select team from WaitingTeam team where team.id = :waitingTeamId")
    Optional<WaitingTeam> findByIdForUpdate(@Param("waitingTeamId") long waitingTeamId);

    boolean existsByStoreIdAndBusinessDateAndStatus(
            long storeId,
            LocalDate businessDate,
            WaitingTeamStatus status
    );

    @Query("""
            select team
            from WaitingTeam team
            where team.storeId = :storeId
              and team.businessDate = :businessDate
              and team.status = :waitingStatus
            order by team.queueSequence asc, team.id asc
            limit 1
            """)
    Optional<WaitingTeam> findFifoHead(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("waitingStatus") WaitingTeamStatus waitingStatus
    );

    long countByStoreIdAndStatusIn(
            long storeId,
            Collection<WaitingTeamStatus> activeStatuses
    );

    @Query("""
            select count(team)
            from WaitingTeam team
            where team.storeId = :storeId
              and team.businessDate = :businessDate
              and team.queueSequence < :queueSequence
              and team.status in ('WAITING', 'CALLED', 'ARRIVED', 'RESERVATION_CONVERTING')
            """)
    long countActiveAhead(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("queueSequence") long queueSequence
    );

    @Query("select team.id from WaitingTeam team where team.storeId = :storeId and team.status in :statuses order by team.id")
    List<Long> findIdsByStoreIdAndStatusIn(@Param("storeId") long storeId,
            @Param("statuses") Collection<WaitingTeamStatus> statuses);

    @Query("""
            select team
            from WaitingTeam team
            where team.storeId = :storeId
              and team.status in ('WAITING', 'CALLED', 'ARRIVED', 'RESERVATION_CONVERTING')
            order by team.id
            """)
    List<WaitingTeam> findActiveClosureTargets(@Param("storeId") long storeId);

    @Query(value = """
            SELECT target.*
              FROM waiting_teams target
             WHERE target.store_id = :storeId
               AND target.business_date = :businessDate
               AND target.status = 'WAITING'
               AND (
                    SELECT COUNT(*)
                      FROM waiting_teams ahead
                     WHERE ahead.store_id = target.store_id
                       AND ahead.business_date = target.business_date
                       AND ahead.queue_sequence < target.queue_sequence
                       AND ahead.status IN (
                           'WAITING', 'CALLED', 'ARRIVED', 'RESERVATION_CONVERTING'
                       )
               ) <= 2
             ORDER BY target.queue_sequence, target.waiting_team_id
            """, nativeQuery = true)
    List<WaitingTeam> findEntryImminentCandidates(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate
    );

    default List<Long> findTerminalCompensationScanIds(
            long beforeExclusiveWaitingTeamId,
            long upperBoundWaitingTeamId,
            int limit
    ) {
        if (beforeExclusiveWaitingTeamId < 1
                || upperBoundWaitingTeamId < 0
                || limit < 1) {
            throw new IllegalArgumentException("cursor and limit must be valid");
        }
        return findTerminalCompensationScanIds(
                beforeExclusiveWaitingTeamId,
                upperBoundWaitingTeamId,
                PageRequest.of(0, Math.min(limit, 100)));
    }

    @Query("""
            select team.id
            from WaitingTeam team
            where team.id < :beforeExclusiveWaitingTeamId
              and team.id <= :upperBoundWaitingTeamId
            order by team.id desc
            """)
    List<Long> findTerminalCompensationScanIds(
            @Param("beforeExclusiveWaitingTeamId") long beforeExclusiveWaitingTeamId,
            @Param("upperBoundWaitingTeamId") long upperBoundWaitingTeamId,
            Pageable pageable
    );

    @Query("select max(team.id) from WaitingTeam team")
    Long findMaxWaitingTeamId();

    default long findTerminalCompensationScanUpperBoundId() {
        Long upperBound = findMaxWaitingTeamId();
        return upperBound == null ? 0L : upperBound;
    }

    @Query(value = """
            SELECT COALESCE(MAX(t.waiting_team_id), 0) AS maxTeamId
            FROM waiting_teams t
            WHERE t.store_id = :storeId
              AND t.business_date = :businessDate
              AND t.created_at <= :asOf
            """, nativeQuery = true)
    WaitingDashboardCheckpoint dashboardCheckpoint(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("asOf") Instant asOf
    );

    interface WaitingDashboardCheckpoint {
        Long getMaxTeamId();
    }
}
