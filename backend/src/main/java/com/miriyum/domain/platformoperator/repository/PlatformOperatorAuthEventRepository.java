package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuthEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformOperatorAuthEventRepository extends JpaRepository<PlatformOperatorAuthEvent, Long> {
    List<PlatformOperatorAuthEvent> findAllByAccountIdOrderByOccurredAtAsc(Long accountId);
}
