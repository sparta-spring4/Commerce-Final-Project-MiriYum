package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEventPublicationState;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 공개 웨이팅 상태 사건의 팀 순서와 미발행 조회를 소유한다. */
public interface WaitingStatusEventRepository extends JpaRepository<WaitingStatusEvent, Long> {

    List<WaitingStatusEvent> findByPublicationStateOrderByIdAsc(
            WaitingStatusEventPublicationState publicationState,
            Pageable pageable
    );
}
