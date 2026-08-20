package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuthEvent;
import java.util.List;
import java.time.Instant;
import java.util.Optional;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformOperatorAuthEventRepository extends JpaRepository<PlatformOperatorAuthEvent, Long> {
    List<PlatformOperatorAuthEvent> findAllByAccountIdOrderByOccurredAtAsc(Long accountId);

    @Query("""
            select max(event.occurredAt) from PlatformOperatorAuthEvent event
             where event.accountId = :accountId
               and event.eventType = :eventType
               and event.outcome = :outcome
            """)
    Optional<Instant> findLatestOccurredAt(
            @Param("accountId") long accountId,
            @Param("eventType") PlatformOperatorAuthEventType eventType,
            @Param("outcome") PlatformOperatorAuthEventOutcome outcome);
}
