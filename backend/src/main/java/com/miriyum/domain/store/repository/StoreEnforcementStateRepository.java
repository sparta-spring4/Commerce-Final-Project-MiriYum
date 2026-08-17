package com.miriyum.domain.store.repository;

import com.miriyum.domain.store.entity.StoreEnforcementState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreEnforcementStateRepository extends JpaRepository<StoreEnforcementState, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from StoreEnforcementState state where state.storeId = :storeId")
    Optional<StoreEnforcementState> findByStoreIdForUpdate(@Param("storeId") long storeId);

    Optional<StoreEnforcementState> findByStoreId(long storeId);

}
