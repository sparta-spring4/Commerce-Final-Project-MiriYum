package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingEntryImminentEvent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaitingEntryImminentEventRepository
        extends JpaRepository<WaitingEntryImminentEvent, Long> {

    Optional<WaitingEntryImminentEvent> findByWaitingTeamId(long waitingTeamId);
}
