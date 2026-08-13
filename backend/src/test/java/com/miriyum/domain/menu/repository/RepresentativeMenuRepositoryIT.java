package com.miriyum.domain.menu.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.menu.schedule.enabled=false"
})
@Transactional
class RepresentativeMenuRepositoryIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @Autowired
    private RepresentativeMenuSettingRepository settingRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

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
        long firstMenuId = saveMenu(
                store.getId(), store.getStoreOperatorAccountId(), "first");
        long secondMenuId = saveMenu(
                store.getId(), store.getStoreOperatorAccountId(), "second");
        insertSetting(store.getId());
        insertEntry(store.getId(), 1, firstMenuId);

        assertThatThrownBy(() -> insertEntry(store.getId(), 1, secondMenuId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateMenuMembership() {
        Store store = saveStore("representative-menu@example.com", "2000000003");
        long menuId = saveMenu(
                store.getId(), store.getStoreOperatorAccountId(), "same");
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
}
