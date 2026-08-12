package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingTransitionAudit;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 성공한 웨이팅 전이 감사 사건의 명령 단위 조회를 제공한다. */
public interface WaitingTransitionAuditRepository
        extends JpaRepository<WaitingTransitionAudit, Long> {

    Optional<WaitingTransitionAudit> findByCommandId(String commandId);
}
