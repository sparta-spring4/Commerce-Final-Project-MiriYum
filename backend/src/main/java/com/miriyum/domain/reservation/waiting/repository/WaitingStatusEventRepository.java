package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEventPublicationState;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** 공개 웨이팅 상태 사건의 팀 순서와 미발행 조회를 소유한다. */
public interface WaitingStatusEventRepository extends JpaRepository<WaitingStatusEvent, Long> {

    List<WaitingStatusEvent> findByPublicationStateOrderByIdAsc(
            WaitingStatusEventPublicationState publicationState,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WaitingStatusEvent> findFirstByPublicationStateOrderByIdAsc(
            WaitingStatusEventPublicationState publicationState
    );

    Optional<WaitingStatusEvent> findByWaitingTeamIdAndEventSequence(
            long waitingTeamId,
            long eventSequence
    );
}
