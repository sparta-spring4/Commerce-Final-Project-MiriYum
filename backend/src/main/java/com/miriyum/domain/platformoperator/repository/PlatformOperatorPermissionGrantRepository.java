package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorPermissionGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformOperatorPermissionGrantRepository extends JpaRepository<PlatformOperatorPermissionGrant, Long> {
    List<PlatformOperatorPermissionGrant> findAllByPlatformOperatorAccountId(Long platformOperatorAccountId);

    boolean existsByPlatformOperatorAccountIdAndPermission(
            Long platformOperatorAccountId,
            PlatformOperatorPermission permission);

    long deleteByPlatformOperatorAccountIdAndPermission(
            Long platformOperatorAccountId,
            PlatformOperatorPermission permission);
}
