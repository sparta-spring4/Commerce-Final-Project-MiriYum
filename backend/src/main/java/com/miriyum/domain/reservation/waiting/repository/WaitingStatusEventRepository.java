package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEventPublicationState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 공개 웨이팅 상태 사건의 팀 순서와 미발행 조회를 소유한다. */
public interface WaitingStatusEventRepository extends JpaRepository<WaitingStatusEvent, Long> {

    List<WaitingStatusEvent> findByPublicationStateOrderByIdAsc(
            WaitingStatusEventPublicationState publicationState,
            Pageable pageable
    );

    @Query(value = """
            SELECT
                COALESCE(SUM(CASE WHEN e.public_status = 'WAITING' THEN 1 ELSE 0 END), 0)
                    AS waitingTeams,
                COALESCE(SUM(CASE WHEN e.public_status = 'CALLED' THEN 1 ELSE 0 END), 0)
                    AS calledTeams,
                COALESCE(SUM(CASE WHEN e.public_status = 'WAITING' THEN t.party_size ELSE 0 END), 0)
                    AS waitingPeople,
                COALESCE(SUM(CASE WHEN e.public_status = 'CALLED' THEN t.party_size ELSE 0 END), 0)
                    AS calledPeople,
                TIMESTAMPDIFF(SECOND,
                    MIN(CASE WHEN e.public_status IN ('WAITING', 'CALLED')
                        THEN t.created_at ELSE NULL END),
                    :asOf) AS longestWaitSeconds,
                COALESCE(SUM(CASE WHEN e.public_status = 'NO_SHOW' THEN 1 ELSE 0 END), 0)
                    AS confirmedNoShowTeams,
                COALESCE(MAX(e.waiting_status_event_id), 0) AS maxEventId,
                COALESCE(MAX(e.event_sequence), 0) AS maxEventSequence,
                CAST(UNIX_TIMESTAMP(MAX(e.occurred_at)) * 1000000 AS SIGNED)
                    AS dataThroughEpochMicros
            FROM waiting_teams t
            JOIN waiting_status_events e
              ON e.waiting_team_id = t.waiting_team_id
             AND e.event_sequence = (
                 SELECT MAX(latest.event_sequence)
                 FROM waiting_status_events latest
                 WHERE latest.waiting_team_id = t.waiting_team_id
                   AND latest.occurred_at <= :asOf
             )
            WHERE t.store_id = :storeId
              AND t.business_date = :businessDate
              AND t.created_at <= :asOf
            """, nativeQuery = true)
    WaitingDashboardAggregate aggregateDashboardState(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("asOf") Instant asOf
    );

    interface WaitingDashboardAggregate {
        Long getWaitingTeams();
        Long getCalledTeams();
        Long getWaitingPeople();
        Long getCalledPeople();
        Long getLongestWaitSeconds();
        Long getConfirmedNoShowTeams();
        Long getMaxEventId();
        Long getMaxEventSequence();
        Long getDataThroughEpochMicros();
    }
}
