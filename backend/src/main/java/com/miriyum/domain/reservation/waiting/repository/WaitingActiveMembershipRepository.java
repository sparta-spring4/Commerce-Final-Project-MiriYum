package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 활성 소비자·매장 중복 잠금 행의 영속성을 소유한다. */
public interface WaitingActiveMembershipRepository
        extends JpaRepository<WaitingActiveMembership, Long> {

    Optional<WaitingActiveMembership> findByStoreIdAndConsumerAccountId(
            long storeId,
            long consumerAccountId
    );

    long deleteByWaitingTeamId(long waitingTeamId);
}
