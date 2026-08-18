package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface PlatformOperatorAccountRepository extends JpaRepository<PlatformOperatorAccount, Long> {
    interface AccountQueryRow {
        Long getOperatorId();
        String getEmail();
        String getDisplayName();
        String getStatus();
        String getPasswordState();
        long getAuthorityVersion();
        LocalDateTime getLastLoginAt();
    }

    @Query(value = """
            select a.platform_operator_account_id as operatorId,
                   a.email as email,
                   a.display_name as displayName,
                   a.status as status,
                   a.password_state as passwordState,
                   a.authority_version as authorityVersion,
                   max(case when e.event_type = 'LOGIN' and e.outcome = 'SUCCESS' then e.occurred_at end) as lastLoginAt
              from platform_operator_accounts a
              left join platform_operator_auth_events e
                on e.platform_operator_account_id = a.platform_operator_account_id
             where (:status is null or a.status = :status)
               and (:role is null or exists (
                   select 1 from platform_operator_role_grants r
                    where r.platform_operator_account_id = a.platform_operator_account_id and r.role = :role))
               and (:query is null
                    or a.platform_operator_account_id = :queryId
                    or instr(lower(a.email), lower(:query)) > 0
                    or instr(lower(a.display_name), lower(:query)) > 0)
             group by a.platform_operator_account_id, a.email, a.display_name, a.status,
                      a.password_state, a.authority_version
             order by
               case when :sortField = 'operatorId' and :sortDirection = 'asc' then a.platform_operator_account_id end asc,
               case when :sortField = 'operatorId' and :sortDirection = 'desc' then a.platform_operator_account_id end desc,
               case when :sortField = 'displayName' and :sortDirection = 'asc' then a.display_name end asc,
               case when :sortField = 'displayName' and :sortDirection = 'desc' then a.display_name end desc,
               case when :sortField = 'status' and :sortDirection = 'asc' then a.status end asc,
               case when :sortField = 'status' and :sortDirection = 'desc' then a.status end desc,
               case when :sortField = 'lastLoginAt' and max(case when e.event_type = 'LOGIN' and e.outcome = 'SUCCESS' then e.occurred_at end) is null then 1 else 0 end asc,
               case when :sortField = 'lastLoginAt' and :sortDirection = 'asc' then max(case when e.event_type = 'LOGIN' and e.outcome = 'SUCCESS' then e.occurred_at end) end asc,
               case when :sortField = 'lastLoginAt' and :sortDirection = 'desc' then max(case when e.event_type = 'LOGIN' and e.outcome = 'SUCCESS' then e.occurred_at end) end desc,
               a.platform_operator_account_id asc
            """, countQuery = """
            select count(*) from platform_operator_accounts a
             where (:status is null or a.status = :status)
               and (:role is null or exists (
                   select 1 from platform_operator_role_grants r
                    where r.platform_operator_account_id = a.platform_operator_account_id and r.role = :role))
               and (:query is null
                    or a.platform_operator_account_id = :queryId
                    or instr(lower(a.email), lower(:query)) > 0
                    or instr(lower(a.display_name), lower(:query)) > 0)
            """, nativeQuery = true)
    Page<AccountQueryRow> searchAccounts(
            @Param("status") String status,
            @Param("role") String role,
            @Param("query") String query,
            @Param("queryId") Long queryId,
            @Param("sortField") String sortField,
            @Param("sortDirection") String sortDirection,
            Pageable pageable);

    Optional<PlatformOperatorAccount> findByEmail(String email);

    boolean existsByEmail(String email);

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
