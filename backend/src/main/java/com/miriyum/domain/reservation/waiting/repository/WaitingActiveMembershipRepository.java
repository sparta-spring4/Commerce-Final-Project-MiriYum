package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 계정 전체의 활성 웨이팅 중복 잠금 행 영속성을 소유한다. */
public interface WaitingActiveMembershipRepository
        extends JpaRepository<WaitingActiveMembership, Long> {

    Optional<WaitingActiveMembership> findByConsumerAccountId(long consumerAccountId);

    long deleteByWaitingTeamId(long waitingTeamId);
}
