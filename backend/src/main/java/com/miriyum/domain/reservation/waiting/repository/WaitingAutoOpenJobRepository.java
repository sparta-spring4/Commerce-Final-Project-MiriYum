package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingAutoOpenJob;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitingAutoOpenJobRepository extends JpaRepository<WaitingAutoOpenJob, Long> {
    Optional<WaitingAutoOpenJob> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO waiting_auto_open_jobs (
                store_id,
                business_interval_key,
                business_date,
                interval_starts_at,
                interval_ends_at,
                scheduled_at,
                expected_settings_version,
                expected_advance_open_minutes,
                idempotency_key,
                status,
                attempt_count,
                next_attempt_at,
                fencing_token,
                created_at,
                updated_at
            ) VALUES (
                :#{#job.storeId},
                :#{#job.businessIntervalKey},
                :#{#job.businessDate},
                :#{#job.intervalStartsAt},
                :#{#job.intervalEndsAt},
                :#{#job.scheduledAt},
                :#{#job.expectedSettingsVersion},
                :#{#job.expectedAdvanceOpenMinutes},
                :#{#job.idempotencyKey},
                'PENDING',
                0,
                :#{#job.nextAttemptAt},
                0,
                :#{#job.createdAt},
                :#{#job.updatedAt}
            )
            """, nativeQuery = true)
    int insertPending(@Param("job") WaitingAutoOpenJob job);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE waiting_auto_open_jobs job
            SET job.status = 'PENDING',
                job.attempt_count = 0,
                job.next_attempt_at = GREATEST(job.scheduled_at, :now),
                job.lease_owner = NULL,
                job.lease_until = NULL,
                job.fencing_token = job.fencing_token + 1,
                job.failure_code = NULL,
                job.last_attempted_at = NULL,
                job.completed_at = NULL,
                job.updated_at = :now
            WHERE job.store_id = :#{#job.storeId}
              AND job.business_interval_key = :#{#job.businessIntervalKey}
              AND job.business_date = :#{#job.businessDate}
              AND job.interval_starts_at = :#{#job.intervalStartsAt}
              AND job.interval_ends_at = :#{#job.intervalEndsAt}
              AND job.scheduled_at = :#{#job.scheduledAt}
              AND job.expected_settings_version = :#{#job.expectedSettingsVersion}
              AND job.expected_advance_open_minutes = :#{#job.expectedAdvanceOpenMinutes}
              AND job.idempotency_key = :#{#job.idempotencyKey}
              AND job.status = 'INVALIDATED'
              AND job.failure_code = 'STALE_INTERVAL'
              AND job.interval_ends_at > :now
            """, nativeQuery = true)
    int rearmInvalidated(
            @Param("job") WaitingAutoOpenJob job,
            @Param("now") Instant now);

    @Query(value = """
            SELECT job.waiting_auto_open_job_id
            FROM waiting_auto_open_jobs job
            WHERE (
                    job.status IN ('PENDING', 'RETRY_WAIT')
                    AND job.next_attempt_at <= :now
                  )
               OR (
                    job.status = 'PROCESSING'
                    AND job.lease_until <= :now
                  )
            ORDER BY COALESCE(job.next_attempt_at, job.lease_until),
                     job.waiting_auto_open_job_id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findClaimableIds(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job
            from WaitingAutoOpenJob job
            where job.id in :ids
            order by job.scheduledAt, job.id
            """)
    List<WaitingAutoOpenJob> findAllForUpdateByIdIn(@Param("ids") List<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from WaitingAutoOpenJob job where job.id = :id")
    Optional<WaitingAutoOpenJob> findByIdForUpdate(@Param("id") long id);

    @Modifying
    @Query(value = """
            UPDATE waiting_auto_open_jobs job
            SET job.status = 'INVALIDATED',
                job.next_attempt_at = NULL,
                job.failure_code = 'STALE_SETTINGS',
                job.completed_at = :now,
                job.updated_at = :now
            WHERE job.waiting_auto_open_job_id IN (
                SELECT stale.waiting_auto_open_job_id
                FROM (
                    SELECT candidate.waiting_auto_open_job_id
                    FROM waiting_auto_open_jobs candidate
                    LEFT JOIN waiting_settings setting
                      ON setting.store_id = candidate.store_id
                    WHERE candidate.status IN ('PENDING', 'RETRY_WAIT')
                      AND (
                           setting.waiting_setting_id IS NULL
                           OR setting.enabled = FALSE
                           OR setting.reception_mode <> 'AUTO'
                           OR setting.version <> candidate.expected_settings_version
                           OR setting.advance_open_minutes
                              <> candidate.expected_advance_open_minutes
                      )
                    ORDER BY candidate.waiting_auto_open_job_id
                    LIMIT :batchSize
                ) stale
            )
            """, nativeQuery = true)
    int invalidateStaleUnclaimed(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);
}
