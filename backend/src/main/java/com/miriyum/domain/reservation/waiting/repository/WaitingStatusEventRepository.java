package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEventPublicationState;
import java.time.Instant;
import java.time.LocalDate;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 공개 웨이팅 상태 사건의 팀 순서와 미발행 조회를 소유한다. */
public interface WaitingStatusEventRepository extends JpaRepository<WaitingStatusEvent, Long> {

    @Query(value = """
            SELECT COALESCE(MAX(event.waiting_status_event_id), 0) AS watermark,
                   own.store_id AS storeId,
                   own.business_date AS businessDate
              FROM waiting_active_memberships membership
              JOIN waiting_teams own
                ON own.waiting_team_id = membership.waiting_team_id
              LEFT JOIN waiting_teams candidate
                ON candidate.store_id = own.store_id
               AND candidate.business_date = own.business_date
               AND (candidate.waiting_team_id = own.waiting_team_id
                    OR candidate.queue_sequence < own.queue_sequence)
              LEFT JOIN waiting_status_events event
                ON event.waiting_team_id = candidate.waiting_team_id
             WHERE membership.consumer_account_id = :consumerAccountId
             GROUP BY own.store_id, own.business_date
            """, nativeQuery = true)
    Optional<ActiveConsumerSseHighWatermark> findActiveConsumerSseHighWatermark(
            @Param("consumerAccountId") long consumerAccountId
    );

    @Query(value = """
            SELECT COALESCE(MAX(event.waiting_status_event_id), 0)
              FROM waiting_teams latest
              LEFT JOIN waiting_status_events event
                ON event.waiting_team_id = latest.waiting_team_id
             WHERE latest.waiting_team_id = (
                   SELECT MAX(owned.waiting_team_id)
                     FROM waiting_teams owned
                    WHERE owned.consumer_account_id = :consumerAccountId
             )
            """, nativeQuery = true)
    long findLatestOwnedConsumerSseHighWatermark(
            @Param("consumerAccountId") long consumerAccountId
    );

    @Query(value = """
            SELECT COALESCE(MAX(event.waiting_status_event_id), 0)
              FROM waiting_teams team
              LEFT JOIN waiting_status_events event
                ON event.waiting_team_id = team.waiting_team_id
             WHERE team.store_id = :storeId
            """, nativeQuery = true)
    long findStoreSseHighWatermark(@Param("storeId") long storeId);

    interface ActiveConsumerSseHighWatermark {
        Long getWatermark();
        Long getStoreId();
        LocalDate getBusinessDate();
    }

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WaitingStatusEvent> findFirstByPublicationStateOrderByIdAsc(
            WaitingStatusEventPublicationState publicationState
    );

    Optional<WaitingStatusEvent> findByWaitingTeamIdAndEventSequence(
            long waitingTeamId,
            long eventSequence
    );
}
