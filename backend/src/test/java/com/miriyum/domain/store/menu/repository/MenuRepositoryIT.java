package com.miriyum.domain.store.menu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVersionStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.menu.model.AllergenDisclosure;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.AllergenIngredientCode;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.OriginDisclosure;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.generate_statistics=true",
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

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @Transactional
    void storesImmutableVersionCollectionsUsingFlywaySchema() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create("menu-owner@example.com", "hashed", "owner"))
                .getId();
        Store store = storeRepository.saveAndFlush(Store.create(
                operatorId, "9876543210", BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 7, 31, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
        Menu saved = menuRepository.saveAndFlush(Menu.create(
                store.getId(),
                new MenuContent(
                        "Americano", "", 5_000, true, "BEVERAGE",
                        List.of("DESSERT"), List.of("signature"),
                        true, true,
                        DisclosureRegistrationStatus.REGISTERED,
                        List.of(new AllergenDisclosure(
                                AllergenIngredientCode.MILK,
                                AllergenDisclosureStatus.CONTAINS)),
                        DisclosureRegistrationStatus.REGISTERED,
                        List.of(new OriginDisclosure("원두", "콜롬비아")),
                        false),
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

    @Test
    @Transactional
    void findsOnlyCurrentSelectableMenuSnapshotsWithOneQuery() {
        long operatorId = saveOperator("selection-owner@example.com");
        Store store = saveStore(operatorId, "1000000001", true, true);
        Store otherStore = saveStore(operatorId, "1000000002", true, true);
        Instant now = Instant.parse("2026-08-06T00:00:00Z");

        Menu americano = savePublishedMenu(
                store.getId(), operatorId, content("Americano", 5_000, true), now);
        americano.appendDraft(
                content("Unpublished Americano", 9_000, true), operatorId, now.plusSeconds(1));
        menuRepository.saveAndFlush(americano);
        Menu latte = savePublishedMenu(
                store.getId(), operatorId, content("Latte", 6_000, true), now);
        saveMenu(store.getId(), operatorId, content("Draft", 1_000, true), now);
        savePublishedMenu(
                store.getId(), operatorId, content("Hold Disabled", 2_000, false), now);
        Menu hidden = savePublishedMenu(
                store.getId(), operatorId, content("Hidden", 3_000, true), now);
        hidden.changeVisibility(MenuVisibility.HIDDEN);
        menuRepository.saveAndFlush(hidden);
        Menu paused = savePublishedMenu(
                store.getId(), operatorId, content("Paused", 4_000, true), now);
        paused.changeSellingStatus(MenuSellingStatus.PAUSED);
        menuRepository.saveAndFlush(paused);
        savePublishedMenu(
                otherStore.getId(), operatorId, content("Other Store", 7_000, true), now);
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();

        List<MenuHoldSelectionRow> rows =
                menuRepository.findMenuHoldSelectionRows(store.getId());

        assertThat(rows).filteredOn(row -> row.getMenuId() != null)
                .extracting(
                        MenuHoldSelectionRow::getMenuId,
                        MenuHoldSelectionRow::getMenuName,
                        MenuHoldSelectionRow::getUnitPrice)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                americano.getId(), "Americano", 5_000),
                        org.assertj.core.groups.Tuple.tuple(
                                latte.getId(), "Latte", 6_000));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
    }

    @Test
    @Transactional
    void distinguishesMissingStoreFromExistingStoreWithoutCandidates() {
        long operatorId = saveOperator("empty-selection-owner@example.com");
        Store reservationDisabled = saveStore(
                operatorId, "1000000011", false, true);
        Store menuHoldDisabled = saveStore(
                operatorId, "1000000012", true, false);
        Store noCandidate = saveStore(
                operatorId, "1000000013", true, true);
        entityManager.flush();
        entityManager.clear();

        assertThat(menuRepository.findMenuHoldSelectionRows(reservationDisabled.getId()))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getMenuId()).isNull());
        assertThat(menuRepository.findMenuHoldSelectionRows(menuHoldDisabled.getId()))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getMenuId()).isNull());
        assertThat(menuRepository.findMenuHoldSelectionRows(noCandidate.getId()))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getMenuId()).isNull());
        assertThat(menuRepository.findMenuHoldSelectionRows(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    @Transactional
    void excludesMenusWhenStoreIsTemporarilyClosedOrClosed() {
        long operatorId = saveOperator("closed-selection-owner@example.com");
        Store temporarilyClosed = saveStore(
                operatorId, "1000000021", true, true);
        Store closed = saveStore(
                operatorId, "1000000022", true, true);
        Instant now = Instant.parse("2026-08-06T00:00:00Z");
        savePublishedMenu(
                temporarilyClosed.getId(), operatorId,
                content("Temporarily Closed Menu", 5_000, true), now);
        savePublishedMenu(
                closed.getId(), operatorId,
                content("Closed Menu", 6_000, true), now);
        temporarilyClosed.update(
                null, null, null, null, null, null,
                null, null, null, OperationStatus.TEMPORARILY_CLOSED);
        closed.close();
        storeRepository.saveAndFlush(temporarilyClosed);
        storeRepository.saveAndFlush(closed);
        entityManager.flush();
        entityManager.clear();

        assertThat(menuRepository.findMenuHoldSelectionRows(temporarilyClosed.getId()))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getMenuId()).isNull());
        assertThat(menuRepository.findMenuHoldSelectionRows(closed.getId()))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getMenuId()).isNull());
    }

    private long saveOperator(String email) {
        return operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(email, "hashed", "owner")).getId();
    }

    private Store saveStore(
            long operatorId,
            String registrationNumber,
            boolean reservationEnabled,
            boolean menuHoldEnabled
    ) {
        return storeRepository.saveAndFlush(Store.create(
                operatorId, registrationNumber, BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                reservationEnabled, menuHoldEnabled, true, "Asia/Seoul",
                LocalDateTime.of(2026, 8, 6, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
    }

    private Menu savePublishedMenu(
            long storeId,
            long operatorId,
            MenuContent content,
            Instant now
    ) {
        Menu menu = saveMenu(storeId, operatorId, content, now);
        menu.publish(now);
        return menuRepository.saveAndFlush(menu);
    }

    private Menu saveMenu(
            long storeId,
            long operatorId,
            MenuContent content,
            Instant now
    ) {
        return menuRepository.saveAndFlush(Menu.create(
                storeId, content, operatorId, now));
    }

    private MenuContent content(String name, int price, boolean holdSelectionAllowed) {
        return new MenuContent(
                name, "", price, false, "BEVERAGE", List.of(), List.of(),
                holdSelectionAllowed, false,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.NOT_APPLICABLE,
                List.of(),
                false);
    }
}
