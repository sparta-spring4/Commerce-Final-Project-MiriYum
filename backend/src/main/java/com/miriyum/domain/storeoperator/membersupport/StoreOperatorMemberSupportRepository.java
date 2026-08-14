package com.miriyum.domain.storeoperator.membersupport;

import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreOperatorMemberSupportRepository extends JpaRepository<StoreOperatorAccount, Long> {
    Optional<StoreOperatorAccount> findByEmailAndPhone(String email, String phone);

    @Query(value = "SELECT * FROM store_operator_accounts WHERE store_operator_account_id = :accountId FOR UPDATE",
            nativeQuery = true)
    Optional<StoreOperatorAccount> findByIdForUpdate(@Param("accountId") long accountId);

    @Query("""
            SELECT account FROM StoreOperatorAccount account
             WHERE (:joinedFrom IS NULL OR account.createdAt >= :joinedFrom)
               AND (:joinedTo IS NULL OR account.createdAt <= :joinedTo)
             ORDER BY account.createdAt ASC, account.id ASC
            """)
    Page<StoreOperatorAccount> search(
            @Param("joinedFrom") LocalDateTime joinedFrom,
            @Param("joinedTo") LocalDateTime joinedTo,
            Pageable pageable);
}
