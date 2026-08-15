package com.miriyum.domain.consumer.membersupport;

import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsumerMemberSupportRepository extends JpaRepository<ConsumerAccount, Long> {

    Optional<ConsumerAccount> findByEmailAndPhone(String email, String phone);

    @Query(value = "SELECT * FROM consumer_accounts WHERE consumer_account_id = :accountId FOR UPDATE",
            nativeQuery = true)
    Optional<ConsumerAccount> findByIdForUpdate(@Param("accountId") long accountId);

    @Query("""
            SELECT account FROM ConsumerAccount account
             WHERE (:joinedFrom IS NULL OR account.createdAt >= :joinedFrom)
               AND (:joinedTo IS NULL OR account.createdAt <= :joinedTo)
               AND (:accountStatus IS NULL OR account.status = :accountStatus)
               AND (:passwordResetRequired IS NULL
                    OR account.passwordResetRequired = :passwordResetRequired)
             ORDER BY account.createdAt DESC, account.id DESC
            """)
    Page<ConsumerAccount> search(
            @Param("joinedFrom") LocalDateTime joinedFrom,
            @Param("joinedTo") LocalDateTime joinedTo,
            @Param("accountStatus") ConsumerAccountStatus accountStatus,
            @Param("passwordResetRequired") Boolean passwordResetRequired,
            Pageable pageable);
}
