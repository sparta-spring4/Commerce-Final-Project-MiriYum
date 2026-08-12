package com.miriyum.domain.schedule.repository;

import com.miriyum.domain.schedule.entity.StoreScheduleState;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreScheduleStateRepository
        extends JpaRepository<StoreScheduleState, Long> {

    List<StoreScheduleState> findAllByStoreIdIn(Collection<Long> storeIds);

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO store_schedule_state (
                store_id,
                next_operating_version,
                next_reservation_version,
                created_at,
                updated_at
            )
            VALUES (:storeId, 1, 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """, nativeQuery = true)
    void initialize(@Param("storeId") long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select state
            from StoreScheduleState state
            where state.storeId = :storeId
            """)
    Optional<StoreScheduleState> findForUpdateByStoreId(
            @Param("storeId") long storeId);
}
