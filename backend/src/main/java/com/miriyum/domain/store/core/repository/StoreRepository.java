package com.miriyum.domain.store.core.repository;

import com.miriyum.domain.store.core.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {
}
