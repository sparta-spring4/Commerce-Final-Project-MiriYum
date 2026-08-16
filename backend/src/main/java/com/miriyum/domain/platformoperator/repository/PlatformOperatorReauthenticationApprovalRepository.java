package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorReauthenticationApproval;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformOperatorReauthenticationApprovalRepository
        extends JpaRepository<PlatformOperatorReauthenticationApproval, Long> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update platform_operator_reauthentication_approvals
               set consumed_at = :revokedAt
             where platform_operator_account_id = :operatorId
               and consumed_at is null
            """, nativeQuery = true)
    int revokeUnconsumedByOperatorId(
            @Param("operatorId") long operatorId,
            @Param("revokedAt") Instant revokedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update platform_operator_reauthentication_approvals
               set consumed_at = :consumedAt
             where approval_digest = :digest
               and platform_operator_account_id = :operatorId
               and purpose = :purpose
               and target_type = :targetType
               and target_id = :targetId
               and session_fingerprint = :sessionFingerprint
               and authority_version = :authorityVersion
               and consumed_at is null
               and expires_at > :consumedAt
            """, nativeQuery = true)
    int consumeBoundApproval(
            @Param("digest") String digest,
            @Param("operatorId") long operatorId,
            @Param("purpose") String purpose,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("sessionFingerprint") String sessionFingerprint,
            @Param("authorityVersion") long authorityVersion,
            @Param("consumedAt") Instant consumedAt);
}
