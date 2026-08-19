package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingTransitionAudit;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 성공한 웨이팅 전이 감사 사건의 명령 단위 조회를 제공한다. */
public interface WaitingTransitionAuditRepository
        extends JpaRepository<WaitingTransitionAudit, Long> {

    @Query("""
            select audit.waitingTeamId as waitingTeamId,
                   team.storeId as storeId,
                   audit.beforeStatus as beforeStatus,
                   audit.afterStatus as afterStatus,
                   audit.resultVersion as resultVersion,
                   audit.occurredAt as occurredAt
              from WaitingTransitionAudit audit, WaitingTeam team
             where team.id = audit.waitingTeamId
               and audit.occurredAt between :changedFrom and :changedTo
               and (:storeId is null or team.storeId = :storeId)
               and (:allStatuses = true or audit.afterStatus in :statuses)
               and (
                    :afterChangedAt is null
                    or audit.occurredAt < :afterChangedAt
                    or (
                        audit.occurredAt = :afterChangedAt
                        and concat('waiting:', cast(audit.waitingTeamId as string)) < :afterCaseId
                    )
               )
               and not exists (
                    select newer.id
                      from WaitingTransitionAudit newer
                     where newer.waitingTeamId = audit.waitingTeamId
                       and newer.occurredAt between :changedFrom and :changedTo
                       and (:allStatuses = true or newer.afterStatus in :statuses)
                       and (
                            newer.occurredAt > audit.occurredAt
                            or (newer.occurredAt = audit.occurredAt and newer.id > audit.id)
                       )
               )
             order by audit.occurredAt desc,
                      concat('waiting:', cast(audit.waitingTeamId as string)) desc,
                      audit.id desc
            """)
    List<MonitoringTransition> findMonitoringChanges(
            @Param("changedFrom") Instant changedFrom,
            @Param("changedTo") Instant changedTo,
            @Param("storeId") Long storeId,
            @Param("allStatuses") boolean allStatuses,
            @Param("statuses") Set<WaitingTeamStatus> statuses,
            @Param("afterChangedAt") Instant afterChangedAt,
            @Param("afterCaseId") String afterCaseId,
            Pageable pageable);

    @Query("""
            select audit.waitingTeamId as waitingTeamId,
                   team.storeId as storeId,
                   audit.beforeStatus as beforeStatus,
                   audit.afterStatus as afterStatus,
                   audit.resultVersion as resultVersion,
                   audit.occurredAt as occurredAt
              from WaitingTransitionAudit audit, WaitingTeam team
             where team.id = audit.waitingTeamId
               and audit.waitingTeamId in :waitingTeamIds
             order by audit.waitingTeamId, audit.resultVersion, audit.id
            """)
    List<MonitoringTransition> findMonitoringHistory(
            @Param("waitingTeamIds") List<Long> waitingTeamIds);

    Optional<WaitingTransitionAudit> findByCommandId(String commandId);

    interface MonitoringTransition {
        long getWaitingTeamId();
        long getStoreId();
        WaitingTeamStatus getBeforeStatus();
        WaitingTeamStatus getAfterStatus();
        long getResultVersion();
        Instant getOccurredAt();
    }
}
