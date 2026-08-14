package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuthorityGuard;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PlatformOperatorAuthorityGuardRepository
        extends JpaRepository<PlatformOperatorAuthorityGuard, Byte> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select guard from PlatformOperatorAuthorityGuard guard where guard.id = 1")
    Optional<PlatformOperatorAuthorityGuard> lockSingleton();
}
