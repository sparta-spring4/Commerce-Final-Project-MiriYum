package com.miriyum.domain.store.repository;

import com.miriyum.domain.store.entity.Store;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.miriyum.domain.store.enums.OperationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findAllByStoreOperatorAccountIdOrderByIdAsc(long operatorAccountId);

    boolean existsByIdAndStoreOperatorAccountId(long storeId, long operatorAccountId);

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

    @Query("select store from Store store where (:keyword is null or lower(store.name) like lower(concat('%',:keyword,'%'))) and (:status is null or store.operationStatus=:status)")
    Page<Store> searchForPlatformAdministration(@Param("keyword") String keyword,
                                                @Param("status") OperationStatus status,
                                                Pageable pageable);
}
