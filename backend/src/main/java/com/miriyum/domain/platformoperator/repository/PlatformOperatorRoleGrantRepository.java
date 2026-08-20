package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformOperatorRoleGrantRepository extends JpaRepository<PlatformOperatorRoleGrant, Long> {
    List<PlatformOperatorRoleGrant> findAllByPlatformOperatorAccountId(Long platformOperatorAccountId);

    List<PlatformOperatorRoleGrant> findAllByPlatformOperatorAccountIdIn(Collection<Long> platformOperatorAccountIds);

    boolean existsByPlatformOperatorAccountIdAndRole(Long platformOperatorAccountId, PlatformOperatorRole role);

    long deleteByPlatformOperatorAccountIdAndRole(Long platformOperatorAccountId, PlatformOperatorRole role);

    @Query(value = """
            select count(*)
              from platform_operator_role_grants grants
              join platform_operator_accounts accounts
                on accounts.platform_operator_account_id = grants.platform_operator_account_id
             where grants.role = 'SUPER_ADMIN'
               and accounts.status = 'ACTIVE'
            """, nativeQuery = true)
    long countActiveSuperAdministrators();

    @Query(value = """
            select count(*)
              from platform_operator_role_grants grants
              join platform_operator_accounts accounts
                on accounts.platform_operator_account_id = grants.platform_operator_account_id
             where grants.platform_operator_account_id = :operatorId
               and grants.role = 'SUPER_ADMIN'
               and accounts.status = 'ACTIVE'
            """, nativeQuery = true)
    long countActiveSuperAdministratorById(@Param("operatorId") long operatorId);
}
