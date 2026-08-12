package com.miriyum.domain.search.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.menu.repository.RepresentativeMenuSettingRepository;
import com.miriyum.domain.menu.service.RepresentativeMenuQueryService;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.search.dto.publicapi.PublicMenu;
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
class StorePublicReadRepositoryIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @Autowired
    private StorePublicReadRepository publicReadRepository;

    @Autowired
    private RepresentativeMenuQueryService representativeMenuQueryService;

    @Autowired
    private RepresentativeMenuSettingRepository settingRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void derivesMembershipFromCurrentSettingAndDefensivelyFiltersIneligibleMenus() {
        long operatorId = operatorRepository.saveAndFlush(StoreOperatorAccount.create(
                "public-representative@example.com", "hashed", "owner")).getId();
        Store store = storeRepository.saveAndFlush(Store.create(
                operatorId, "3000000001", BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 8, 13, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        Menu soldOut = savePublished(store.getId(), operatorId, "sold-out", false, now);
        Menu paused = savePublished(store.getId(), operatorId, "paused", false, now);
        Menu selling = savePublished(store.getId(), operatorId, "selling", false, now);
        Menu hidden = savePublished(store.getId(), operatorId, "hidden", false, now);
        Menu retired = savePublished(store.getId(), operatorId, "retired", false, now);
        Menu legacyRepresentative = savePublished(
                store.getId(), operatorId, "legacy", true, now);

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
                .extracting(PublicMenu::name, PublicMenu::representative,
                        PublicMenu::saleStatus)
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
}
