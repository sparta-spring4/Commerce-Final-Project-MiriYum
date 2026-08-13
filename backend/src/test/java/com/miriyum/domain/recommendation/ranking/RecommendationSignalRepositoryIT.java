package com.miriyum.domain.recommendation.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.recommendation.repository.RecommendationSignalRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.model.OriginDisclosure;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.storeoperator.dto.auth.StoreOperatorSignUpRequest;
import com.miriyum.domain.storeoperator.service.StoreOperatorAuthService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
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
@Tag("integration")
@Tag("integration-shard-d")
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.menu.schedule.enabled=false",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "miriyum.identity-verification.dev-stub-enabled=true",
            "spring.task.scheduling.enabled=false"
        })
class RecommendationSignalRepositoryIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired RecommendationSignalRepository repository;
    @Autowired StoreRepository storeRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired StoreOperatorAuthService storeOperatorAuthService;
    @Autowired EntityManager entityManager;

    @Test
    @Transactional
    void returnsDistinctCurrentSignalsForEveryInputStoreInInputOrder() {
        long operatorId = createOperator();
        Store target = createStore(
                operatorId,
                "추천 대상",
                "CAFE_BAKERY",
                Set.of("DATE", "QUIET", "FAMILY"));
        Store empty = createStore(operatorId, "신호 없음", "KOREAN", Set.of("FAMILY"));

        Menu beverage = publishMenu(
                target,
                "라떼",
                "BEVERAGE",
                List.of("DESSERT", "BAKERY"),
                MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE,
                false);
        Menu brunch = publishMenu(
                target,
                "샌드위치",
                "ETC",
                List.of("BAKERY"),
                MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE,
                false);
        publishMenu(
                target,
                "판매 중지",
                "BEVERAGE",
                List.of("SEAFOOD"),
                MenuSellingStatus.PAUSED,
                MenuVisibility.VISIBLE,
                false);
        publishMenu(
                target,
                "숨김",
                "BEVERAGE",
                List.of("SEAFOOD"),
                MenuSellingStatus.SELLING,
                MenuVisibility.HIDDEN,
                false);
        publishMenu(
                target,
                "은퇴",
                "BEVERAGE",
                List.of("SEAFOOD"),
                MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE,
                true);
        entityManager.flush();
        entityManager.clear();

        RecommendationSearchSignals condition = new RecommendationSearchSignals(
                List.of("CAFE_BAKERY"),
                List.of("BEVERAGE", "DESSERT", "BAKERY"),
                List.of("DATE", "QUIET", "GROUP"));

        Map<Long, RecommendationCandidateSignals> result = repository.findSignals(
                List.of(empty.getId(), target.getId(), 999_999L), condition);

        assertThat(result.keySet())
                .containsExactly(empty.getId(), target.getId(), 999_999L);
        assertThat(result.get(empty.getId()))
                .isEqualTo(RecommendationCandidateSignals.empty());
        assertThat(result.get(999_999L))
                .isEqualTo(RecommendationCandidateSignals.empty());
        assertThat(result.get(target.getId()))
                .isEqualTo(new RecommendationCandidateSignals(
                        true,
                        true,
                        2,
                        2,
                        Set.of(beverage.getId(), brunch.getId())));
    }

    private long createOperator() {
        int sequence = SEQUENCE.incrementAndGet();
        return Long.parseLong(storeOperatorAuthService.signUp(
                new StoreOperatorSignUpRequest(
                        "recommendation-owner-" + sequence + "@example.com",
                        "Password123!",
                        "Password123!",
                        "010-1000-0001",
                        "추천 운영자")).accountId());
    }

    private Store createStore(
            long operatorId,
            String name,
            String categoryCode,
            Set<String> tags
    ) {
        int sequence = SEQUENCE.incrementAndGet();
        Store store = Store.create(
                operatorId,
                String.format("%010d", 9_000_000 + sequence),
                BusinessType.CAFE,
                name,
                "추천 신호 통합 테스트 매장",
                Region.SEOUL,
                "테스트 주소",
                categoryCode,
                tags,
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 8, 6, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1");
        return storeRepository.saveAndFlush(store);
    }

    private Menu publishMenu(
            Store store,
            String name,
            String primaryCategory,
            List<String> secondaryCategories,
            MenuSellingStatus sellingStatus,
            MenuVisibility visibility,
            boolean retired
    ) {
        MenuContent content = new MenuContent(
                name,
                "",
                10_000,
                true,
                primaryCategory,
                secondaryCategories,
                List.of(),
                true,
                true,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new OriginDisclosure("원재료", "대한민국")),
                false);
        Menu menu = Menu.create(store.getId(), content, store.getStoreOperatorAccountId(), NOW);
        menu.publish(NOW.plusSeconds(1));
        menu.changeSellingStatus(sellingStatus);
        menu.changeVisibility(visibility);
        if (retired) {
            menu.retire(NOW.plusSeconds(2));
        }
        return menuRepository.saveAndFlush(menu);
    }
}
