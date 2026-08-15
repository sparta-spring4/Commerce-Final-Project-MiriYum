package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
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
            select team
            from WaitingTeam team
            where team.storeId = :storeId
              and team.status in ('WAITING', 'CALLED', 'ARRIVED', 'RESERVATION_CONVERTING')
            order by team.id
            """)
    List<WaitingTeam> findActiveClosureTargets(@Param("storeId") long storeId);

    default List<Long> findMissingTerminalCompensationCandidateIds(
            long afterWaitingTeamId,
            long upperBoundWaitingTeamId,
            int limit
    ) {
        if (afterWaitingTeamId < 0
                || upperBoundWaitingTeamId < afterWaitingTeamId
                || limit < 1) {
            throw new IllegalArgumentException("cursor and limit must be valid");
        }
        return findMissingTerminalCompensationCandidateIds(
                afterWaitingTeamId,
                upperBoundWaitingTeamId,
                List.of(WaitingTeamStatus.CANCELLED, WaitingTeamStatus.CLOSED_BY_STORE),
                PageRequest.of(0, Math.min(limit, 100)));
    }

    @Query("""
            select team.id
            from WaitingTeam team
            where team.id > :afterWaitingTeamId
              and team.id <= :upperBoundWaitingTeamId
              and team.status in :terminalStatuses
              and team.waitingPaymentId is not null
              and not exists (
                  select compensation.id
                  from WaitingConversionCompensation compensation
                  where compensation.waitingTeamId = team.id
                    and compensation.paymentId = team.waitingPaymentId
              )
            order by team.id
            """)
    List<Long> findMissingTerminalCompensationCandidateIds(
            @Param("afterWaitingTeamId") long afterWaitingTeamId,
            @Param("upperBoundWaitingTeamId") long upperBoundWaitingTeamId,
            @Param("terminalStatuses") Collection<WaitingTeamStatus> terminalStatuses,
            Pageable pageable
    );

    @Query("""
            select max(team.id)
            from WaitingTeam team
            where team.status in :terminalStatuses
              and team.waitingPaymentId is not null
              and not exists (
                  select compensation.id
                  from WaitingConversionCompensation compensation
                  where compensation.waitingTeamId = team.id
                    and compensation.paymentId = team.waitingPaymentId
              )
            """)
    Long findMaxMissingTerminalCompensationCandidateId(
            @Param("terminalStatuses") Collection<WaitingTeamStatus> terminalStatuses);

    default long findMissingTerminalCompensationUpperBoundId() {
        Long upperBound = findMaxMissingTerminalCompensationCandidateId(
                List.of(WaitingTeamStatus.CANCELLED, WaitingTeamStatus.CLOSED_BY_STORE));
        return upperBound == null ? 0L : upperBound;
    }
}
