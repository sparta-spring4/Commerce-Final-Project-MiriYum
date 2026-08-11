package com.miriyum.domain.storeoperator.repository;

import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreOperatorAccountRepository extends JpaRepository<StoreOperatorAccount, Long> {

    Optional<StoreOperatorAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    @Query(value = "SELECT * FROM store_operator_accounts "
            + "WHERE store_operator_account_id = :accountId FOR UPDATE", nativeQuery = true)
    Optional<StoreOperatorAccount> findByIdForUpdate(@Param("accountId") Long accountId);
}
