package com.miriyum.domain.consumer.repository;

import com.miriyum.domain.consumer.entity.ConsumerAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsumerAccountRepository extends JpaRepository<ConsumerAccount, Long> {

    Optional<ConsumerAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    @Query(value = "SELECT * FROM consumer_accounts "
            + "WHERE consumer_account_id = :accountId FOR UPDATE", nativeQuery = true)
    Optional<ConsumerAccount> findByIdForUpdate(@Param("accountId") Long accountId);
}
