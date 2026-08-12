package com.miriyum.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.menu.dto.storeoperator.MenuVisibilityRequest;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuReplaceRequest;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.menu.repository.RepresentativeMenuSettingRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.menu.schedule.enabled=false"
})
class RepresentativeMenuConcurrencyIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @Autowired
    private RepresentativeMenuService representativeMenuService;

    @Autowired
    private MenuCommandService menuCommandService;

    @Autowired
    private RepresentativeMenuSettingRepository settingRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM representative_menu_audits");
        jdbcTemplate.execute("DELETE FROM representative_menu_entries");
        jdbcTemplate.execute("DELETE FROM representative_menu_settings");
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

    @Test
    void concurrentReplacementsWithSameExpectedVersionHaveOneWinner() throws Exception {
        Fixture fixture = fixture("concurrent-replace@example.com", "4000000001");
        List<String> forward = fixture.menuIds().stream().map(String::valueOf).toList();
        List<String> reverse = List.of(
                forward.get(2), forward.get(1), forward.get(0));
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<Attempt> attempts = runTogether(
                () -> replaceAttempt(
                        barrier, fixture, key(201),
                        new RepresentativeMenuReplaceRequest(0L, forward)),
                () -> replaceAttempt(
                        barrier, fixture, key(202),
                        new RepresentativeMenuReplaceRequest(0L, reverse)));

        assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> !attempt.succeeded())
                .extracting(Attempt::errorCode)
                .containsExactly(CommonErrorCode.CONCURRENT_MODIFICATION);
        var setting = settingRepository.findDetailedByStoreId(fixture.storeId()).orElseThrow();
        assertThat(setting.getVersion()).isEqualTo(1L);
        assertThat(List.of(
                fixture.menuIds(),
                List.of(
                        fixture.menuIds().get(2),
                        fixture.menuIds().get(1),
                        fixture.menuIds().get(0))))
                .contains(setting.orderedMenuIds());
    }

    @Test
    void replacementRacingHiddenNeverLeavesHiddenMenuSelected() throws Exception {
        Fixture fixture = fixture("concurrent-hidden@example.com", "4000000002");
        representativeMenuService.replace(
                fixture.operatorId(), fixture.storeId(), key(210),
                new RepresentativeMenuReplaceRequest(
                        0L, fixture.menuIds().stream().map(String::valueOf).toList()));
        long hiddenMenuId = fixture.menuIds().get(0);
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<Attempt> attempts = runTogether(
                () -> replaceAttempt(
                        barrier, fixture, key(211),
                        new RepresentativeMenuReplaceRequest(
                                1L,
                                List.of(
                                        String.valueOf(fixture.menuIds().get(2)),
                                        String.valueOf(fixture.menuIds().get(1)),
                                        String.valueOf(hiddenMenuId)))),
                () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        menuCommandService.changeVisibility(
                                fixture.operatorId(), fixture.storeId(), hiddenMenuId,
                                key(212),
                                new MenuVisibilityRequest(MenuVisibility.HIDDEN, "hidden"));
                        return Attempt.success();
                    } catch (ServiceException exception) {
                        return Attempt.failure(exception.getErrorCode());
                    }
                });

        assertThat(attempts.get(1).succeeded()).isTrue();
        assertThat(attempts.get(0).succeeded()
                || attempts.get(0).errorCode() == CommonErrorCode.CONCURRENT_MODIFICATION)
                .isTrue();
        var menu = menuRepository.findManagedById(hiddenMenuId).orElseThrow();
        var setting = settingRepository.findDetailedByStoreId(fixture.storeId()).orElseThrow();
        assertThat(menu.getVisibility()).isEqualTo(MenuVisibility.HIDDEN);
        assertThat(setting.orderedMenuIds()).doesNotContain(hiddenMenuId);
    }

    private Attempt replaceAttempt(
            CyclicBarrier barrier,
            Fixture fixture,
            IdempotencyKey key,
            RepresentativeMenuReplaceRequest request
    ) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            representativeMenuService.replace(
                    fixture.operatorId(), fixture.storeId(), key, request);
            return Attempt.success();
        } catch (ServiceException exception) {
            return Attempt.failure(exception.getErrorCode());
        }
    }

    private List<Attempt> runTogether(
            Callable<Attempt> first,
            Callable<Attempt> second
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> firstFuture = executor.submit(first);
            Future<Attempt> secondFuture = executor.submit(second);
            return List.of(
                    firstFuture.get(20, TimeUnit.SECONDS),
                    secondFuture.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Fixture fixture(String email, String registrationNumber) {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(email, "hashed", "owner")).getId();
        Store store = storeRepository.saveAndFlush(Store.create(
                operatorId, registrationNumber, BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 8, 13, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        List<Long> menuIds = List.of(
                savePublished(store.getId(), operatorId, "first", now).getId(),
                savePublished(store.getId(), operatorId, "second", now).getId(),
                savePublished(store.getId(), operatorId, "third", now).getId());
        return new Fixture(operatorId, store.getId(), menuIds);
    }

    private Menu savePublished(
            long storeId,
            long operatorId,
            String name,
            Instant now
    ) {
        Menu menu = Menu.create(
                storeId,
                new MenuContent(
                        name, "", 5_000, false, "BEVERAGE",
                        List.of(), List.of(), true, true,
                        DisclosureRegistrationStatus.REGISTERED,
                        List.of(new AllergenDisclosure(
                                AllergenIngredientCode.MILK,
                                AllergenDisclosureStatus.CONTAINS)),
                        DisclosureRegistrationStatus.NOT_APPLICABLE,
                        List.of(), false),
                operatorId,
                now);
        menu.publish(now);
        return menuRepository.saveAndFlush(menu);
    }

    private IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(
                "550e8400-e29b-41d4-a716-44665544" + String.format("%04d", suffix));
    }

    private record Fixture(long operatorId, long storeId, List<Long> menuIds) {
    }

    private record Attempt(boolean succeeded, ErrorCode errorCode) {
        private static Attempt success() {
            return new Attempt(true, null);
        }

        private static Attempt failure(ErrorCode errorCode) {
            return new Attempt(false, errorCode);
        }
    }
}
