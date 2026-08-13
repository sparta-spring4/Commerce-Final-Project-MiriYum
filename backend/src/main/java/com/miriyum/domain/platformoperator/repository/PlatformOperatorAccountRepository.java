package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformOperatorAccountRepository extends JpaRepository<PlatformOperatorAccount, Long> {
    Optional<PlatformOperatorAccount> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from PlatformOperatorAccount account where account.id = :id")
    Optional<PlatformOperatorAccount> findByIdForUpdate(@Param("id") Long id);
}
