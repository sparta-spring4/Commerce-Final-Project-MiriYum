package com.miriyum.domain.storeoperator.repository;

import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreOperatorAccountRepository extends JpaRepository<StoreOperatorAccount, Long> {

    Optional<StoreOperatorAccount> findByEmail(String email);

    boolean existsByEmail(String email);
}
