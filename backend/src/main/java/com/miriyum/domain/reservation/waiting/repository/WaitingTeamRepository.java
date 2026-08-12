package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 웨이팅 팀 잠금, FIFO 선두와 활성 수 조회를 소유한다. */
public interface WaitingTeamRepository extends JpaRepository<WaitingTeam, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select team from WaitingTeam team where team.id = :waitingTeamId")
    Optional<WaitingTeam> findByIdForUpdate(@Param("waitingTeamId") long waitingTeamId);

    @Query("""
            select team
            from WaitingTeam team
            where team.storeId = :storeId
              and team.businessDate = :businessDate
              and team.status = :waitingStatus
            order by team.queueSequence asc, team.id asc
            limit 1
            """)
    Optional<WaitingTeam> findFifoHead(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("waitingStatus") WaitingTeamStatus waitingStatus
    );

    long countByStoreIdAndStatusIn(
            long storeId,
            Collection<WaitingTeamStatus> activeStatuses
    );
}
