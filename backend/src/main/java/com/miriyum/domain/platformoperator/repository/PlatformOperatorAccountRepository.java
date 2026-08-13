package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface PlatformOperatorAccountRepository extends JpaRepository<PlatformOperatorAccount, Long> {
    Optional<PlatformOperatorAccount> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from PlatformOperatorAccount account where account.id = :id")
    Optional<PlatformOperatorAccount> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update platform_operator_accounts
               set temporary_password_failure_count = temporary_password_failure_count + 1,
                   row_version = row_version + 1
             where platform_operator_account_id = :id
               and status = 'ACTIVE'
               and password_state = 'TEMPORARY'
            """, nativeQuery = true)
    int incrementTemporaryPasswordFailure(@Param("id") Long id);
}
