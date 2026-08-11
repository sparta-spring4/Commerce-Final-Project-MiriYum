package com.miriyum.domain.menuhold.inventory.repository;

import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryPolicyAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuInventoryPolicyAuditRepository
        extends JpaRepository<MenuInventoryPolicyAudit, Long> {
}
