package com.miriyum.domain.store.menu.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.menu.dto.MenuContentRequest;
import com.miriyum.domain.store.menu.dto.MenuPublicationRequest;
import com.miriyum.domain.store.menu.entity.MenuPublicationEvent;
import com.miriyum.domain.store.menu.enums.MenuPublicationEventType;
import com.miriyum.domain.store.menu.enums.MenuPublicationMode;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.repository.MenuPublicationEventRepository;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class MenuCommandServiceIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MenuCommandService service;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private MenuPublicationEventRepository eventRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM menu_publication_events");
        jdbcTemplate.execute("DELETE FROM menu_version_local_tags");
        jdbcTemplate.execute("DELETE FROM menu_version_secondary_categories");
        jdbcTemplate.execute("DELETE FROM menu_versions");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
    }

    @Test
    void createReplayAndImmediatePublicationAreAtomic() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create("command-owner@example.com", "hashed", "owner"))
                .getId();
        long storeId = storeRepository.saveAndFlush(Store.create(
                operatorId, "8765432109", BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true)).getId();
        IdempotencyKey createKey =
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440001");

        MenuCommandResult created =
                service.create(operatorId, storeId, createKey, content("Americano"));
        MenuCommandResult replayed =
                service.create(operatorId, storeId, createKey, content("Americano"));
        MenuCommandResult published = service.publish(
                operatorId,
                storeId,
                created.data().menuId(),
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440002"),
                new MenuPublicationRequest(MenuPublicationMode.IMMEDIATE, null));

        assertThat(created.httpStatus()).isEqualTo(201);
        assertThat(replayed.data().menuId()).isEqualTo(created.data().menuId());
        assertThat(menuRepository.count()).isOne();
        assertThat(published.data().draft()).isNull();
        assertThat(published.data().published().versionNumber()).isEqualTo(1);
        assertThat(published.data().visibility()).isEqualTo(MenuVisibility.VISIBLE);
        assertThat(published.data().sellingStatus()).isEqualTo(MenuSellingStatus.SELLING);
        List<MenuPublicationEvent> events =
                eventRepository.findByMenuIdOrderById(created.data().menuId());
        assertThat(events).extracting(MenuPublicationEvent::getEventType)
                .containsExactly(MenuPublicationEventType.PUBLISHED_IMMEDIATELY);
    }

    private MenuContentRequest content(String name) {
        return new MenuContentRequest(
                name, "", 5_000, true, "BEVERAGE",
                List.of("DESSERT"), List.of("signature"),
                true, true);
    }
}
