package com.miriyum.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.menu.dto.storeoperator.AllergenDisclosureRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuChangeReasonRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuContentRequest;
import com.miriyum.domain.menu.dto.storeoperator.OriginDisclosureRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuPublicationRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuSellingStatusRequest;
import com.miriyum.domain.menu.dto.storeoperator.MenuVisibilityRequest;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuReplaceRequest;
import com.miriyum.domain.menu.entity.MenuPublicationEvent;
import com.miriyum.domain.menu.entity.RepresentativeMenuAudit;
import com.miriyum.domain.menu.enums.MenuPublicationEventType;
import com.miriyum.domain.menu.enums.MenuPublicationMode;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVersionStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.menu.enums.RepresentativeMenuAuditActorType;
import com.miriyum.domain.menu.enums.RepresentativeMenuAuditEventType;
import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.repository.MenuPublicationEventRepository;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.menu.repository.RepresentativeMenuAuditRepository;
import com.miriyum.domain.menu.repository.RepresentativeMenuSettingRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
    private RepresentativeMenuService representativeMenuService;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private MenuPublicationEventRepository eventRepository;

    @Autowired
    private RepresentativeMenuAuditRepository representativeAuditRepository;

    @Autowired
    private RepresentativeMenuSettingRepository representativeSettingRepository;

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
    void createReplayAndImmediatePublicationAreAtomic() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create("command-owner@example.com", "hashed", "owner"))
                .getId();
        long storeId = storeRepository.saveAndFlush(Store.create(
                operatorId, "8765432109", BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 7, 31, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        IdempotencyKey createKey =
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440001");

        MenuCommandResult created =
                service.create(operatorId, storeId, createKey, content("Americano"));
        MenuCommandResult replayed =
                service.create(operatorId, storeId, createKey, content("Americano"));
        MenuCommandResult published = service.publish(
                operatorId,
                storeId,
                Long.parseLong(created.data().menuId()),
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440002"),
                new MenuPublicationRequest(
                        MenuPublicationMode.IMMEDIATE, null, "첫 게시"));

        assertThat(created.httpStatus()).isEqualTo(201);
        assertThat(replayed.data().menuId()).isEqualTo(created.data().menuId());
        assertThat(menuRepository.count()).isOne();
        assertThat(published.data().draft()).isNull();
        assertThat(published.data().published().versionNumber()).isEqualTo(1);
        assertThat(published.data().visibility()).isEqualTo(MenuVisibility.VISIBLE);
        assertThat(published.data().sellingStatus()).isEqualTo(MenuSellingStatus.SELLING);
        List<MenuPublicationEvent> events =
                eventRepository.findByMenuIdOrderById(
                        Long.parseLong(created.data().menuId()));
        assertThat(events).extracting(MenuPublicationEvent::getEventType)
                .containsExactly(
                        MenuPublicationEventType.DRAFT_CREATED,
                        MenuPublicationEventType.PUBLISHED_IMMEDIATELY);
        MenuPublicationEvent draftEvent = events.get(0);
        assertThat(draftEvent.getRequestId()).isEqualTo(createKey.value());
        assertThat(draftEvent.getPreviousVersionNumber()).isNull();
        assertThat(draftEvent.getNewVersionNumber()).isEqualTo(1);
        assertThat(draftEvent.getChangedFields()).contains("NAME", "PRICE");
        MenuPublicationEvent publicationEvent = events.get(1);
        assertThat(publicationEvent.getRequestId())
                .isEqualTo("550e8400-e29b-41d4-a716-446655440002");
        assertThat(publicationEvent.getChangeReason()).isEqualTo("첫 게시");
        assertThat(publicationEvent.getPreviousVisibility()).isEqualTo(MenuVisibility.HIDDEN);
        assertThat(publicationEvent.getNewVisibility()).isEqualTo(MenuVisibility.VISIBLE);
        assertThat(publicationEvent.getPreviousSellingStatus())
                .isEqualTo(MenuSellingStatus.PAUSED);
        assertThat(publicationEvent.getNewSellingStatus())
                .isEqualTo(MenuSellingStatus.SELLING);
    }

    @Test
    void replayAfterStoreClosureReturnsOriginalResponseWithoutFreshValidation() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create("replay-owner@example.com", "hashed", "owner"))
                .getId();
        long storeId = storeRepository.saveAndFlush(Store.create(
                operatorId, "1122334455", BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        IdempotencyKey key =
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440020");
        MenuCommandResult first = service.create(
                operatorId, storeId, key, content("Americano"));
        jdbcTemplate.update(
                "UPDATE stores SET operation_status = 'CLOSED' WHERE store_id = ?",
                storeId);

        MenuCommandResult replay = service.create(
                operatorId, storeId, key, content("Americano"));

        assertThat(replay.data()).isEqualTo(first.data());
        assertThat(menuRepository.count()).isOne();
        assertThat(eventRepository.findByMenuIdOrderById(
                Long.parseLong(first.data().menuId())))
                .extracting(MenuPublicationEvent::getEventType)
                .containsExactly(MenuPublicationEventType.DRAFT_CREATED);
    }

    @Test
    void everyMenuMutationLeavesReasonedAuditTrail() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create("audit-owner@example.com", "hashed", "owner"))
                .getId();
        long storeId = storeRepository.saveAndFlush(Store.create(
                operatorId, "9988776655", BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        long menuId = Long.parseLong(service.create(
                operatorId, storeId, key(30), content("Americano"))
                .data().menuId());
        service.publish(operatorId, storeId, menuId, key(31),
                new MenuPublicationRequest(
                        MenuPublicationMode.SCHEDULED,
                        Instant.now().plusSeconds(3_600),
                        "저녁 메뉴 예약"));
        MenuCommandResult cancelled = service.cancelPublication(
                operatorId, storeId, menuId, key(32),
                new MenuChangeReasonRequest("예약 계획 변경"));
        assertThat(cancelled.data().scheduled()).isNull();
        assertThat(cancelled.data().draft().versionNumber()).isEqualTo(1);
        assertThat(cancelled.data().draft().status()).isEqualTo(MenuVersionStatus.DRAFT);
        assertThat(cancelled.data().draft().effectiveAt()).isNull();
        service.update(operatorId, storeId, menuId, key(33), content("Latte"));
        service.publish(operatorId, storeId, menuId, key(34),
                new MenuPublicationRequest(MenuPublicationMode.IMMEDIATE, null, "신규 게시"));
        service.changeVisibility(operatorId, storeId, menuId, key(35),
                new MenuVisibilityRequest(MenuVisibility.HIDDEN, "일시 숨김"));
        service.changeSellingStatus(operatorId, storeId, menuId, key(36),
                new MenuSellingStatusRequest(
                        MenuSellingStatus.valueOf("SOLD_OUT"), "당일 소진"));
        service.retire(operatorId, storeId, menuId, key(37),
                new MenuChangeReasonRequest("판매 종료"));

        List<MenuPublicationEvent> events = eventRepository.findByMenuIdOrderById(menuId);
        assertThat(events).extracting(MenuPublicationEvent::getEventType)
                .containsExactly(
                        MenuPublicationEventType.DRAFT_CREATED,
                        MenuPublicationEventType.PUBLICATION_SCHEDULED,
                        MenuPublicationEventType.SCHEDULE_CANCELLED,
                        MenuPublicationEventType.DRAFT_UPDATED,
                        MenuPublicationEventType.PUBLISHED_IMMEDIATELY,
                        MenuPublicationEventType.VISIBILITY_CHANGED,
                        MenuPublicationEventType.SELLING_STATUS_CHANGED,
                        MenuPublicationEventType.RETIRED);
        assertThat(events.subList(1, events.size()))
                .extracting(MenuPublicationEvent::getChangeReason)
                .containsExactly(
                        "저녁 메뉴 예약", "예약 계획 변경", null, "신규 게시",
                        "일시 숨김", "당일 소진", "판매 종료");
        assertThat(events).extracting(MenuPublicationEvent::getRequestId)
                .doesNotContainNull();
        assertThat(events.get(3).getChangedFields()).contains("NAME");
        assertThat(events.get(2).getPreviousVersionNumber()).isEqualTo(1);
        assertThat(events.get(2).getNewVersionNumber()).isEqualTo(1);
        assertThat(events.get(2).getEffectiveAt()).isNotNull();
        assertThat(events.get(5).getPreviousVisibility()).isEqualTo(MenuVisibility.VISIBLE);
        assertThat(events.get(5).getNewVisibility()).isEqualTo(MenuVisibility.HIDDEN);
        assertThat(events.get(6).getPreviousSellingStatus()).isEqualTo(MenuSellingStatus.SELLING);
        assertThat(events.get(6).getNewSellingStatus())
                .isEqualTo(MenuSellingStatus.valueOf("SOLD_OUT"));
        assertThat(events.get(7).getNewVersionNumber()).isNull();
    }

    @Test
    void ineligibleStatesAutoRemoveWhileSoldOutAndReplayKeepSelectionStable() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create("auto-remove@example.com", "hashed", "owner"))
                .getId();
        long storeId = storeRepository.saveAndFlush(Store.create(
                operatorId, "4455667788", BusinessType.CAFE, "store", "",
                Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 8, 13, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        List<Long> menuIds = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            long menuId = Long.parseLong(service.create(
                    operatorId, storeId, key(100 + index), content("menu-" + index))
                    .data().menuId());
            service.publish(operatorId, storeId, menuId, key(110 + index),
                    new MenuPublicationRequest(
                            MenuPublicationMode.IMMEDIATE, null, "publish"));
            menuIds.add(menuId);
        }
        representativeMenuService.replace(
                operatorId,
                storeId,
                key(120),
                new RepresentativeMenuReplaceRequest(
                        0L, menuIds.stream().map(String::valueOf).toList()));

        service.changeSellingStatus(operatorId, storeId, menuIds.get(0), key(121),
                new MenuSellingStatusRequest(MenuSellingStatus.SOLD_OUT, "sold out"));
        assertSetting(storeId, 1L, RepresentativeMenuSettingStatus.CONFIGURED, menuIds);

        service.changeVisibility(operatorId, storeId, menuIds.get(1), key(122),
                new MenuVisibilityRequest(MenuVisibility.HIDDEN, "hidden"));
        assertSetting(storeId, 2L, RepresentativeMenuSettingStatus.CONFIGURED,
                List.of(menuIds.get(0), menuIds.get(2), menuIds.get(3), menuIds.get(4)));

        service.changeSellingStatus(operatorId, storeId, menuIds.get(2), key(123),
                new MenuSellingStatusRequest(MenuSellingStatus.PAUSED, "paused"));
        assertSetting(storeId, 3L, RepresentativeMenuSettingStatus.CONFIGURED,
                List.of(menuIds.get(0), menuIds.get(3), menuIds.get(4)));

        IdempotencyKey retireKey = key(124);
        service.retire(operatorId, storeId, menuIds.get(3), retireKey,
                new MenuChangeReasonRequest("retired"));
        service.retire(operatorId, storeId, menuIds.get(3), retireKey,
                new MenuChangeReasonRequest("retired"));
        assertSetting(storeId, 4L, RepresentativeMenuSettingStatus.REQUIRES_ATTENTION,
                List.of(menuIds.get(0), menuIds.get(4)));

        List<RepresentativeMenuAudit> audits =
                representativeAuditRepository.findByStoreIdOrderById(storeId);
        assertThat(audits).extracting(RepresentativeMenuAudit::getEventType)
                .containsExactly(
                        RepresentativeMenuAuditEventType.REPLACED,
                        RepresentativeMenuAuditEventType.AUTO_REMOVED,
                        RepresentativeMenuAuditEventType.AUTO_REMOVED,
                        RepresentativeMenuAuditEventType.AUTO_REMOVED);
        assertThat(audits.subList(1, audits.size()))
                .extracting(RepresentativeMenuAudit::getActorType)
                .containsOnly(RepresentativeMenuAuditActorType.SYSTEM);
        assertThat(audits.subList(1, audits.size()))
                .extracting(RepresentativeMenuAudit::getTriggerMenuId)
                .containsExactly(menuIds.get(1), menuIds.get(2), menuIds.get(3));
    }

    private void assertSetting(
            long storeId,
            long version,
            RepresentativeMenuSettingStatus status,
            List<Long> menuIds
    ) {
        var setting = representativeSettingRepository.findDetailedByStoreId(storeId)
                .orElseThrow();
        assertThat(setting.getVersion()).isEqualTo(version);
        assertThat(setting.getStatus()).isEqualTo(status);
        assertThat(setting.orderedMenuIds()).containsExactlyElementsOf(menuIds);
    }

    private IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(
                "550e8400-e29b-41d4-a716-44665544" + String.format("%04d", suffix));
    }

    private MenuContentRequest content(String name) {
        return new MenuContentRequest(
                name, "", 5_000, true, "BEVERAGE",
                List.of("DESSERT"), List.of("signature"),
                true, true,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosureRequest(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new OriginDisclosureRequest("원두", "콜롬비아")),
                false);
    }
}
