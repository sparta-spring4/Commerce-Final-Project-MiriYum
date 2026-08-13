package com.miriyum.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doAnswer;

import com.miriyum.domain.menu.dto.storeoperator.MenuVisibilityRequest;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuReplaceRequest;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.menu.repository.RepresentativeMenuSettingRepository;
import com.miriyum.domain.search.dto.publicapi.PublicMenu;
import com.miriyum.domain.search.dto.publicapi.PublicStoreDetail;
import com.miriyum.domain.search.repository.StorePublicReadRepository;
import com.miriyum.domain.search.service.StorePublicQueryService;
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
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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

    @MockitoSpyBean
    private StorePublicReadRepository publicReadRepository;

    @Autowired
    private StorePublicQueryService storePublicQueryService;

    @Autowired
    private RepresentativeMenuQueryService representativeMenuQueryService;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

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

    @Test
    void publicDetailKeepsOneSnapshotWhenRepresentativeSettingChangesAfterMenuRead()
            throws Exception {
        Fixture fixture = fixture("public-detail-snapshot@example.com", "4000000003");
        representativeMenuService.replace(
                fixture.operatorId(), fixture.storeId(), key(220),
                new RepresentativeMenuReplaceRequest(
                        0L, fixture.menuIds().stream().map(String::valueOf).toList()));
        long fourthMenuId = savePublished(
                fixture.storeId(), fixture.operatorId(), "fourth",
                Instant.parse("2026-08-13T00:00:00Z")).getId();
        jdbcTemplate.update(
                "UPDATE menus SET visibility = 'HIDDEN' WHERE menu_id = ?",
                fourthMenuId);

        CountDownLatch publicMenusRead = new CountDownLatch(1);
        CountDownLatch replacementCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<PublicMenu> menus = (List<PublicMenu>) invocation.callRealMethod();
            publicMenusRead.countDown();
            assertThat(replacementCommitted.await(10, TimeUnit.SECONDS)).isTrue();
            return menus;
        }).when(publicReadRepository).findPublicMenus(fixture.storeId());

        TransactionTemplate writerTransaction = new TransactionTemplate(transactionManager);
        writerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PublicStoreDetail> detailFuture = executor.submit(() ->
                    storePublicQueryService.getDetail(fixture.storeId(), null, false));
            Future<?> replacementFuture = executor.submit(() -> {
                assertThat(publicMenusRead.await(10, TimeUnit.SECONDS)).isTrue();
                writerTransaction.executeWithoutResult(status -> {
                    jdbcTemplate.update(
                            "UPDATE menus SET visibility = 'VISIBLE' WHERE menu_id = ?",
                            fourthMenuId);
                    jdbcTemplate.update(
                            "DELETE FROM representative_menu_entries WHERE store_id = ?",
                            fixture.storeId());
                    insertEntry(fixture.storeId(), 1, fixture.menuIds().get(1));
                    insertEntry(fixture.storeId(), 2, fixture.menuIds().get(2));
                    insertEntry(fixture.storeId(), 3, fourthMenuId);
                    jdbcTemplate.update("""
                            UPDATE representative_menu_settings
                            SET version = version + 1,
                                status = 'CONFIGURED',
                                lock_version = lock_version + 1,
                                updated_at = UTC_TIMESTAMP(6)
                            WHERE store_id = ?
                            """, fixture.storeId());
                });
                replacementCommitted.countDown();
                return null;
            });

            PublicStoreDetail detail = detailFuture.get(20, TimeUnit.SECONDS);
            replacementFuture.get(20, TimeUnit.SECONDS);

            assertThat(detail.representativeMenus())
                    .extracting(PublicMenu::menuId)
                    .containsExactlyElementsOf(
                            fixture.menuIds().stream().map(String::valueOf).toList());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    @Transactional
    class PersistenceTests {

        @Test
        void lazilyCreatesAndLocksAnUnconfiguredSetting() {
            Store store = saveStore("representative-lazy@example.com", "2000000001");

            settingRepository.ensureExists(store.getId());

            var setting = settingRepository.findByStoreIdForUpdate(store.getId()).orElseThrow();
            assertThat(setting.getVersion()).isZero();
            assertThat(setting.getStatus()).isEqualTo(RepresentativeMenuSettingStatus.UNCONFIGURED);
            assertThat(setting.orderedMenuIds()).isEmpty();
        }

        @Test
        void rejectsDuplicateDisplayOrder() {
            Store store = saveStore("representative-order@example.com", "2000000002");
            long firstMenuId = saveMenu(store.getId(), store.getStoreOperatorAccountId(), "first");
            long secondMenuId = saveMenu(store.getId(), store.getStoreOperatorAccountId(), "second");
            insertSetting(store.getId());
            insertEntry(store.getId(), 1, firstMenuId);

            assertThatThrownBy(() -> insertEntry(store.getId(), 1, secondMenuId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsDuplicateMenuMembership() {
            Store store = saveStore("representative-menu@example.com", "2000000003");
            long menuId = saveMenu(store.getId(), store.getStoreOperatorAccountId(), "same");
            insertSetting(store.getId());
            insertEntry(store.getId(), 1, menuId);

            assertThatThrownBy(() -> insertEntry(store.getId(), 2, menuId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void persistsOrderedEntriesAndSupportsSafeWholeReplacement() {
            Store store = saveStore("representative-aggregate@example.com", "2000000004");
            long operatorId = store.getStoreOperatorAccountId();
            long first = saveMenu(store.getId(), operatorId, "first");
            long second = saveMenu(store.getId(), operatorId, "second");
            long third = saveMenu(store.getId(), operatorId, "third");
            long fourth = saveMenu(store.getId(), operatorId, "fourth");
            settingRepository.ensureExists(store.getId());
            var setting = settingRepository.findByStoreIdForUpdate(store.getId()).orElseThrow();

            setting.replace(List.of(first, second, third));
            settingRepository.saveAndFlush(setting);
            entityManager.clear();

            var reloaded = settingRepository.findByStoreIdForUpdate(store.getId()).orElseThrow();
            assertThat(reloaded.orderedMenuIds()).containsExactly(first, second, third);
            settingRepository.deleteEntriesForReplacement(store.getId());
            var replacement = settingRepository.findByStoreIdForUpdate(store.getId()).orElseThrow();
            replacement.replace(List.of(fourth, third, second));
            settingRepository.saveAndFlush(replacement);
            entityManager.clear();

            var replaced = settingRepository.findByStoreIdForUpdate(store.getId()).orElseThrow();
            assertThat(replaced.getVersion()).isEqualTo(2L);
            assertThat(replaced.orderedMenuIds()).containsExactly(fourth, third, second);
            assertThat(replaced.remove(third)).isTrue();
            settingRepository.saveAndFlush(replaced);
            entityManager.clear();

            var autoRemoved = settingRepository.findByStoreIdForUpdate(store.getId()).orElseThrow();
            assertThat(autoRemoved.getVersion()).isEqualTo(3L);
            assertThat(autoRemoved.getStatus())
                    .isEqualTo(RepresentativeMenuSettingStatus.REQUIRES_ATTENTION);
            assertThat(autoRemoved.orderedMenuIds()).containsExactly(fourth, second);
        }

        @Test
        void derivesPublicMembershipFromCurrentSettingAndFiltersIneligibleMenus() {
            Store store = saveStore("public-representative@example.com", "3000000001");
            long operatorId = store.getStoreOperatorAccountId();
            Instant now = Instant.parse("2026-08-13T00:00:00Z");
            Menu soldOut = savePublished(store.getId(), operatorId, "sold-out", false, now);
            Menu paused = savePublished(store.getId(), operatorId, "paused", false, now);
            Menu selling = savePublished(store.getId(), operatorId, "selling", false, now);
            Menu hidden = savePublished(store.getId(), operatorId, "hidden", false, now);
            Menu retired = savePublished(store.getId(), operatorId, "retired", false, now);
            savePublished(store.getId(), operatorId, "legacy", true, now);

            settingRepository.ensureExists(store.getId());
            var setting = settingRepository.findByStoreIdForUpdate(store.getId()).orElseThrow();
            setting.replace(List.of(
                    soldOut.getId(), paused.getId(), selling.getId(),
                    hidden.getId(), retired.getId()));
            settingRepository.saveAndFlush(setting);

            soldOut.changeSellingStatus(MenuSellingStatus.SOLD_OUT);
            paused.changeSellingStatus(MenuSellingStatus.PAUSED);
            hidden.changeVisibility(MenuVisibility.HIDDEN);
            retired.retire(now.plusSeconds(1));
            menuRepository.saveAllAndFlush(List.of(soldOut, paused, selling, hidden, retired));
            entityManager.clear();

            List<PublicMenu> publicMenus = publicReadRepository.findPublicMenus(store.getId());
            var snapshot = representativeMenuQueryService.getCurrent(store.getId());

            assertThat(publicMenus)
                    .extracting(PublicMenu::name, PublicMenu::representative, PublicMenu::saleStatus)
                    .containsExactlyInAnyOrder(
                            tuple("sold-out", true, MenuSellingStatus.SOLD_OUT),
                            tuple("paused", false, MenuSellingStatus.PAUSED),
                            tuple("selling", true, MenuSellingStatus.SELLING),
                            tuple("legacy", false, MenuSellingStatus.SELLING));
            assertThat(snapshot.items())
                    .extracting(item -> item.menuId())
                    .containsExactly(
                            String.valueOf(soldOut.getId()),
                            String.valueOf(selling.getId()));
            assertThat(snapshot.items())
                    .extracting(item -> item.displayOrder())
                    .containsExactly(1, 2);
            assertThat(snapshot.items().getFirst().sellingStatus())
                    .isEqualTo(MenuSellingStatus.SOLD_OUT);
            assertThat(snapshot.version()).isEqualTo(1L);
        }
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
        Store store = saveStore(email, registrationNumber);
        long operatorId = store.getStoreOperatorAccountId();
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
        return savePublished(storeId, operatorId, name, false, now);
    }

    private Store saveStore(String email, String registrationNumber) {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(email, "hashed", "owner")).getId();
        return storeRepository.saveAndFlush(Store.create(
                operatorId, registrationNumber, BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 8, 13, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
    }

    private long saveMenu(long storeId, long operatorId, String name) {
        MenuContent content = new MenuContent(
                name, "", 1_000, false, "BEVERAGE", List.of(), List.of(),
                true, true,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.NOT_APPLICABLE,
                List.of(), false);
        return menuRepository.saveAndFlush(Menu.create(
                storeId, content, operatorId, Instant.parse("2026-08-13T00:00:00Z"))).getId();
    }

    private Menu savePublished(
            long storeId,
            long operatorId,
            String name,
            boolean legacyRepresentative,
            Instant now
    ) {
        Menu menu = Menu.create(
                storeId,
                new MenuContent(
                        name, "", 5_000, legacyRepresentative, "BEVERAGE",
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

    private void insertSetting(long storeId) {
        jdbcTemplate.update("""
                INSERT INTO representative_menu_settings
                    (store_id, version, status, lock_version, created_at, updated_at)
                VALUES (?, 0, 'UNCONFIGURED', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, storeId);
    }

    private void insertEntry(long storeId, int displayOrder, long menuId) {
        jdbcTemplate.update("""
                INSERT INTO representative_menu_entries (store_id, display_order, menu_id)
                VALUES (?, ?, ?)
                """, storeId, displayOrder, menuId);
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
