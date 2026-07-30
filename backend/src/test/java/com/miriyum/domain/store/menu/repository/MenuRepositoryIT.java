package com.miriyum.domain.store.menu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.enums.MenuVersionStatus;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.menu.schedule.enabled=false"
        })
class MenuRepositoryIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void storesImmutableVersionCollectionsUsingFlywaySchema() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create("menu-owner@example.com", "hashed", "owner"))
                .getId();
        Store store = storeRepository.saveAndFlush(Store.create(
                operatorId, "9876543210", BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true));
        Menu saved = menuRepository.saveAndFlush(Menu.create(
                store.getId(),
                new MenuContent(
                        "Americano", "", 5_000, true, "BEVERAGE",
                        List.of("DESSERT"), List.of("signature"),
                        true, true),
                operatorId,
                Instant.parse("2026-07-31T00:00:00Z")));
        entityManager.clear();

        Menu found = menuRepository.findManagedById(saved.getId()).orElseThrow();

        assertThat(found.getVersions()).hasSize(1);
        assertThat(found.getVersions().getFirst().getStatus())
                .isEqualTo(MenuVersionStatus.DRAFT);
        assertThat(found.getVersions().getFirst().getSecondaryCategoryCodes())
                .containsExactly("DESSERT");
        assertThat(found.getVersions().getFirst().getLocalTags())
                .containsExactly("signature");
    }
}
