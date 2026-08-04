package com.miriyum.domain.store.closure.repository;

import com.miriyum.domain.store.closure.entity.TemporaryClosure;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemporaryClosureRepository extends JpaRepository<TemporaryClosure, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TemporaryClosure c where c.id = :id and c.storeId = :storeId")
    Optional<TemporaryClosure> findForUpdate(@Param("storeId") long storeId, @Param("id") long id);

    @Query("""
            select c from TemporaryClosure c
            where c.storeId in :storeIds and c.cancelledAt is null
              and c.startAt < :endAt and :startAt < c.endAt
            """)
    List<TemporaryClosure> findOverlapping(
            @Param("storeIds") Collection<Long> storeIds,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt);

    @Query("""
            select c from TemporaryClosure c
            where c.storeId = :storeId and c.cancelledAt is null and c.endAt > :effectiveFrom
            """)
    List<TemporaryClosure> findNonCancelledEndingAfter(
            @Param("storeId") long storeId,
            @Param("effectiveFrom") Instant effectiveFrom);
}
