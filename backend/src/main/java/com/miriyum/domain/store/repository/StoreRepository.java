package com.miriyum.domain.store.repository;

import com.miriyum.domain.store.entity.Store;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreRepository extends JpaRepository<Store, Long> {

    @Query("""
            select store.storeOperatorAccountId
            from Store store
            where store.id = :storeId
            """)
    Optional<Long> findOperatorAccountIdById(@Param("storeId") long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select store
            from Store store
            where store.id = :storeId
            """)
    Optional<Store> findByIdForUpdate(@Param("storeId") long storeId);
}
