package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingClosureItemStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJobItem;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 종결 작업에 고정된 팀 항목의 순차 batch claim 잠금을 제공한다. */
public interface WaitingClosureJobItemRepository
        extends JpaRepository<WaitingClosureJobItem, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from WaitingClosureJobItem item where item.id = :id")
    java.util.Optional<WaitingClosureJobItem> findByIdForUpdate(@Param("id") long id);

    long countByWaitingClosureJobIdAndStatus(long jobId, WaitingClosureItemStatus status);

    @Query(value = """
            SELECT * FROM waiting_closure_job_items
            WHERE (status = :pending
               OR (status = :processing AND lease_until <= :now))
              AND waiting_closure_job_item_id > :afterItemId
            ORDER BY waiting_closure_job_item_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WaitingClosureJobItem> findGloballyClaimableForUpdate(
            @Param("pending") String pending,
            @Param("processing") String processing,
            @Param("now") java.time.Instant now,
            @Param("afterItemId") long afterItemId,
            @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from WaitingClosureJobItem item where item.status = :status order by item.id")
    List<WaitingClosureJobItem> findAllByStatusForUpdate(@Param("status") WaitingClosureItemStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select item
            from WaitingClosureJobItem item
            where item.waitingClosureJobId = :waitingClosureJobId
              and item.status = :status
            order by item.id asc
            """)
    List<WaitingClosureJobItem> findClaimableBatchForUpdate(
            @Param("waitingClosureJobId") long waitingClosureJobId,
            @Param("status") WaitingClosureItemStatus status,
            Pageable pageable
    );
}
