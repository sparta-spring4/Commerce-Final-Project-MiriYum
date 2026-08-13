package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingQueueSequence;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 매장 영업일별 중앙 순번 행의 비관적 잠금을 제공한다. */
public interface WaitingQueueSequenceRepository
        extends JpaRepository<WaitingQueueSequence, WaitingQueueSequence.Key> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select sequence
            from WaitingQueueSequence sequence
            where sequence.storeId = :storeId
              and sequence.businessDate = :businessDate
            """)
    Optional<WaitingQueueSequence> findByStoreIdAndBusinessDateForUpdate(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate
    );
}
