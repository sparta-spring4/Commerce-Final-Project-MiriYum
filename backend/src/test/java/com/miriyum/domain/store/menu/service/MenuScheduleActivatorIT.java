package com.miriyum.domain.store.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.menu.dto.AllergenDisclosureRequest;
import com.miriyum.domain.store.menu.dto.MenuContentRequest;
import com.miriyum.domain.store.menu.dto.MenuPublicationRequest;
import com.miriyum.domain.store.menu.dto.OriginDisclosureRequest;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.enums.MenuPublicationEventType;
import com.miriyum.domain.store.menu.enums.MenuPublicationMode;
import com.miriyum.domain.store.menu.enums.MenuVersionStatus;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.AllergenIngredientCode;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.repository.MenuPublicationEventRepository;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
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
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.menu.schedule.enabled=false"
        })
class MenuScheduleActivatorIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MenuCommandService commandService;

    @Autowired
    private MenuScheduleActivator activator;

    @Autowired
    private MenuDatabaseClock databaseClock;

    @Autowired
    private StoreService storeService;

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
        dropFailureConstraint();
        jdbcTemplate.execute("DELETE FROM menu_publication_events");
        jdbcTemplate.execute("DELETE FROM menu_version_origin_disclosures");
        jdbcTemplate.execute("DELETE FROM menu_version_allergen_disclosures");
        jdbcTemplate.execute("DELETE FROM menu_version_local_tags");
        jdbcTemplate.execute("DELETE FROM menu_version_secondary_categories");
        jdbcTemplate.execute("DELETE FROM menu_versions");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
    }

    @AfterEach
    void removeFailureConstraint() {
        dropFailureConstraint();
    }

    @Test
    void concurrentWorkersActivateSameDueMenuExactlyOnce() throws Exception {
        Fixture fixture = scheduledMenu("worker-owner@example.com", "1234567890", 1);
        makeDue(fixture.menuId());
        Menu due = menuRepository.findManagedById(fixture.menuId()).orElseThrow();
        assertThat(due.getScheduledVersionNumber()).isEqualTo(1);
        assertThat(due.getVersions().getFirst().getEffectiveAt())
                .isBefore(Instant.now().plusSeconds(30));
        assertThat(due.getVersions().getFirst().getEffectiveAt())
                .isBefore(databaseClock.now());
        assertThat(storeService.inspectScheduledActivation(fixture.storeId())
                .activationAllowed()).isTrue();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(() -> activateTogether(
                    fixture.menuId(), ready, start));
            Future<Boolean> second = executor.submit(() -> activateTogether(
                    fixture.menuId(), ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        }

        Menu menu = menuRepository.findManagedById(fixture.menuId()).orElseThrow();
        assertThat(menu.getScheduledVersionNumber()).isNull();
        assertThat(menu.getPublishedVersionNumber()).isEqualTo(1);
        assertThat(eventRepository.findByMenuIdOrderById(fixture.menuId()))
                .filteredOn(event -> event.getEventType()
                        == MenuPublicationEventType.SCHEDULE_ACTIVATED)
                .hasSize(1);
    }

    @Test
    void activationEventFailureRollsBackPointerAndVersionAtomically() {
        Fixture fixture = scheduledMenu("rollback-owner@example.com", "0987654321", 10);
        makeDue(fixture.menuId());
        jdbcTemplate.execute("""
                ALTER TABLE menu_publication_events
                ADD CONSTRAINT ck_test_fail_schedule_activation
                CHECK (event_type <> 'SCHEDULE_ACTIVATED')
                """);

        assertThatThrownBy(() -> activator.activateDue(fixture.menuId()))
                .isInstanceOf(RuntimeException.class);

        Menu menu = menuRepository.findManagedById(fixture.menuId()).orElseThrow();
        assertThat(menu.getScheduledVersionNumber()).isEqualTo(1);
        assertThat(menu.getPublishedVersionNumber()).isNull();
        assertThat(menu.getVersions().getFirst().getStatus())
                .isEqualTo(MenuVersionStatus.SCHEDULED);
        assertThat(eventRepository.findByMenuIdOrderById(fixture.menuId()))
                .noneMatch(event -> event.getEventType()
                        == MenuPublicationEventType.SCHEDULE_ACTIVATED);
    }

    @Test
    void dueQuerySkipsClosedStoreBeforeApplyingBatchLimit() {
        Fixture closed = scheduledMenu("closed-owner@example.com", "1111111111", 20);
        Store closedStore = storeRepository.findById(closed.storeId()).orElseThrow();
        closedStore.close();
        storeRepository.saveAndFlush(closedStore);
        makeDue(closed.menuId());

        Fixture eligible = scheduledMenu("eligible-owner@example.com", "2222222222", 30);
        makeDue(eligible.menuId());

        assertThat(menuRepository.findDueScheduledIds(
                databaseClock.now(), PageRequest.of(0, 1)))
                .containsExactly(eligible.menuId());
    }

    private boolean activateTogether(
            long menuId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        return activator.activateDue(menuId);
    }

    private Fixture scheduledMenu(String email, String businessNumber, int keySuffix) {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(email, "hashed", "owner")).getId();
        long storeId = storeRepository.saveAndFlush(Store.create(
                operatorId, businessNumber, BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        long menuId = Long.parseLong(commandService.create(
                operatorId, storeId, key(keySuffix), content()).data().menuId());
        commandService.publish(
                operatorId,
                storeId,
                menuId,
                key(keySuffix + 1),
                new MenuPublicationRequest(
                        MenuPublicationMode.SCHEDULED,
                        Instant.now().plusSeconds(300),
                        "예약 게시"));
        return new Fixture(menuId, storeId);
    }

    private void makeDue(long menuId) {
        int updated = jdbcTemplate.update("""
                UPDATE menu_versions
                SET effective_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
                WHERE menu_id = ? AND status = 'SCHEDULED'
                """, menuId);
        assertThat(updated).isEqualTo(1);
    }

    private IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(
                "550e8400-e29b-41d4-a716-44665544" + String.format("%04d", suffix));
    }

    private MenuContentRequest content() {
        return new MenuContentRequest(
                "Americano", "", 5_000, true, "BEVERAGE",
                List.of(), List.of("signature"), true, true,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosureRequest(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new OriginDisclosureRequest("원두", "콜롬비아")),
                false);
    }

    private void dropFailureConstraint() {
        Integer present = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'menu_publication_events'
                  AND constraint_name = 'ck_test_fail_schedule_activation'
                """, Integer.class);
        if (present != null && present > 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE menu_publication_events
                    DROP CHECK ck_test_fail_schedule_activation
                    """);
        }
    }

    private record Fixture(long menuId, long storeId) {
    }
}
