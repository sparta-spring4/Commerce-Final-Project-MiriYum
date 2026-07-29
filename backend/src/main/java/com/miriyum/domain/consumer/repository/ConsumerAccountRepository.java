package com.miriyum.domain.consumer.repository;

import com.miriyum.domain.consumer.entity.ConsumerAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumerAccountRepository extends JpaRepository<ConsumerAccount, Long> {

    Optional<ConsumerAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
