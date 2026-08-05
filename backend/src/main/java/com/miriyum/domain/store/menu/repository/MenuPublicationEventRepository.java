package com.miriyum.domain.store.menu.repository;

import com.miriyum.domain.store.menu.entity.MenuPublicationEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuPublicationEventRepository
        extends JpaRepository<MenuPublicationEvent, Long> {

    List<MenuPublicationEvent> findByMenuIdOrderById(long menuId);
}
