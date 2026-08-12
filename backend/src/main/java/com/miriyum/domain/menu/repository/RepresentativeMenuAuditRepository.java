package com.miriyum.domain.menu.repository;

import com.miriyum.domain.menu.entity.RepresentativeMenuAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepresentativeMenuAuditRepository
        extends JpaRepository<RepresentativeMenuAudit, Long> {
}
