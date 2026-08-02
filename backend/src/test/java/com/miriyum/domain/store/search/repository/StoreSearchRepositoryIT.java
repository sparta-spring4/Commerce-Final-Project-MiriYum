package com.miriyum.domain.store.search.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.model.AllergenDisclosure;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.AllergenIngredientCode;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.menu.model.OriginDisclosure;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import com.miriyum.domain.store.search.model.StoreSearchQuery;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class StoreSearchRepositoryIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private StoreSearchRepository repository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("매장명·게시 메뉴명·지역 한글명으로 공개 매장을 중복 없이 검색한다")
    void searchesPublicStoresByNameMenuAndRegionWithoutDuplicates() {
        // given
        Store seoul = createStore("성수 키친", Region.SEOUL, "KOREAN", false);
        publishMenu(seoul, "파스타 100%_특선", MenuSellingStatus.SOLD_OUT, MenuVisibility.VISIBLE,
                false);
        publishMenu(seoul, "파스타 두번째", MenuSellingStatus.PAUSED, MenuVisibility.VISIBLE,
                false);
        createStore("폐점 파스타", Region.SEOUL, "KOREAN", true);
        Store wildcardDecoy = createStore("와일드카드 유사 매장", Region.BUSAN, "KOREAN", false);
        publishMenu(wildcardDecoy, "100ABC특선", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        createStore("부산 식당", Region.BUSAN, "KOREAN", false);
        flushAndClear();

        // when
        PageSnapshot byName = snapshot(query("성수", null, null, "name,asc", 0, 20));
        PageSnapshot byMenu = snapshot(query("100%_특선", null, null, "name,asc", 0, 20));
        PageSnapshot byCommonMenu = snapshot(query("파스타", null, null, "name,asc", 0, 20));
        PageSnapshot byRegion = snapshot(query("서울", null, null, "name,asc", 0, 20));
        StoreSearchCandidate candidate = repository.search(
                query("성수", null, null, "name,asc", 0, 20)).getContent().getFirst();

        // then
        assertThat(byName.storeIds()).containsExactly(seoul.getId());
        assertThat(byMenu.storeIds()).containsExactly(seoul.getId());
        assertThat(byCommonMenu.storeIds()).containsExactly(seoul.getId());
        assertThat(byCommonMenu.totalElements()).isEqualTo(1);
        assertThat(byRegion.storeIds()).containsExactly(seoul.getId());
        assertThat(byRegion.totalElements()).isEqualTo(1);
        assertThat(candidate).satisfies(found -> {
            assertThat(found.name()).isEqualTo("성수 키친");
            assertThat(found.region()).isEqualTo(Region.SEOUL);
            assertThat(found.address()).isEqualTo("테스트 주소");
            assertThat(found.storeCategoryCode()).isEqualTo("KOREAN");
            assertThat(found.operationStatus()).isEqualTo(OperationStatus.OPEN);
            assertThat(found.reservationEnabled()).isTrue();
            assertThat(found.menuHoldEnabled()).isTrue();
            assertThat(found.pickupEnabled()).isTrue();
            assertThat(found.createdAt()).isNotNull();
        });
    }

    @Test
    @Transactional
    @DisplayName("승인되지 않은 매장은 공개 검색에서 제외한다")
    void excludesUnapprovedStores() {
        // given
        jdbcTemplate.execute("""
                CREATE TEMPORARY TABLE stores (
                    store_id BIGINT PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    region VARCHAR(20) NOT NULL,
                    address VARCHAR(300) NOT NULL,
                    store_category_code VARCHAR(50) NOT NULL,
                    operation_status VARCHAR(30) NOT NULL,
                    verification_status VARCHAR(20) NOT NULL,
                    reservation_enabled BOOLEAN NOT NULL,
                    menu_hold_enabled BOOLEAN NOT NULL,
                    pickup_enabled BOOLEAN NOT NULL,
                    created_at DATETIME NOT NULL
                )
                """);
        try {
            jdbcTemplate.update("""
                    INSERT INTO stores (
                        store_id, name, region, address, store_category_code,
                        operation_status, verification_status,
                        reservation_enabled, menu_hold_enabled, pickup_enabled, created_at
                    ) VALUES
                        (9001, '승인 매장', 'SEOUL', '주소', 'KOREAN',
                         'OPEN', 'APPROVED', TRUE, TRUE, TRUE, '2026-08-02 09:00:00'),
                        (9002, '비승인 매장', 'SEOUL', '주소', 'KOREAN',
                         'OPEN', 'PENDING', TRUE, TRUE, TRUE, '2026-08-02 09:00:00')
                    """);

            // when
            PageSnapshot result = snapshot(query(null, null, null, "name,asc", 0, 20));

            // then
            assertThat(result.storeIds()).containsExactly(9001L);
            assertThat(result.totalElements()).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("DROP TEMPORARY TABLE IF EXISTS stores");
        }
    }

    @Test
    @Transactional
    @DisplayName("현재 공개 게시 메뉴만 검색하며 판매중지와 품절 메뉴도 포함한다")
    void searchesOnlyCurrentVisiblePublishedMenusRegardlessOfSellingStatus() {
        // given
        Store store = createStore("메뉴 상태 매장", Region.DAEGU, "CAFE_BAKERY", false);
        publishMenu(store, "품절포함키워드", MenuSellingStatus.SOLD_OUT, MenuVisibility.VISIBLE,
                false);
        publishMenu(store, "중지포함키워드", MenuSellingStatus.PAUSED, MenuVisibility.VISIBLE,
                false);
        publishMenu(store, "숨김제외키워드", MenuSellingStatus.SELLING, MenuVisibility.HIDDEN,
                false);
        publishMenu(store, "폐기제외키워드", MenuSellingStatus.SELLING, MenuVisibility.VISIBLE,
                true);
        createDraftMenu(store, "초안제외키워드");
        replacePublishedMenu(store, "과거게시제외키워드", "현재게시포함키워드");
        flushAndClear();

        // when
        PageSnapshot soldOut = snapshot(query("품절포함키워드", null, null, null, 0, 20));
        PageSnapshot paused = snapshot(query("중지포함키워드", null, null, null, 0, 20));
        PageSnapshot hidden = snapshot(query("숨김제외키워드", null, null, null, 0, 20));
        PageSnapshot retired = snapshot(query("폐기제외키워드", null, null, null, 0, 20));
        PageSnapshot draft = snapshot(query("초안제외키워드", null, null, null, 0, 20));
        PageSnapshot previousPublished = snapshot(query(
                "과거게시제외키워드", null, null, null, 0, 20));
        PageSnapshot currentPublished = snapshot(query(
                "현재게시포함키워드", null, null, null, 0, 20));

        // then
        assertThat(soldOut.storeIds()).containsExactly(store.getId());
        assertThat(paused.storeIds()).containsExactly(store.getId());
        assertThat(hidden.storeIds()).isEmpty();
        assertThat(retired.storeIds()).isEmpty();
        assertThat(draft.storeIds()).isEmpty();
        assertThat(previousPublished.storeIds()).isEmpty();
        assertThat(currentPublished.storeIds()).containsExactly(store.getId());
    }

    @Test
    @Transactional
    @DisplayName("지역·카테고리를 필터링하고 네 정렬 모두 매장 ID로 동률을 해소한다")
    void filtersAndAppliesAllFixedSortsWithStoreIdTieBreaker() {
        // given
        Store firstSameName = createStore("같은 이름", Region.SEOUL, "KOREAN", false);
        Store secondSameName = createStore("같은 이름", Region.SEOUL, "KOREAN", false);
        Store laterName = createStore("하늘 식당", Region.SEOUL, "KOREAN", false);
        createStore("다른 지역", Region.BUSAN, "KOREAN", false);
        createStore("다른 카테고리", Region.SEOUL, "CAFE_BAKERY", false);
        setCreatedAt(firstSameName, "2026-08-01 09:00:00");
        setCreatedAt(secondSameName, "2026-08-01 09:00:00");
        setCreatedAt(laterName, "2026-08-02 09:00:00");
        flushAndClear();

        // when
        List<Long> nameAsc = snapshot(query(null, Region.SEOUL, "KOREAN", "name,asc", 0, 20))
                .storeIds();
        List<Long> nameDesc = snapshot(query(null, Region.SEOUL, "KOREAN", "name,desc", 0, 20))
                .storeIds();
        List<Long> createdAsc = snapshot(query(
                null, Region.SEOUL, "KOREAN", "createdAt,asc", 0, 20)).storeIds();
        List<Long> createdDesc = snapshot(query(
                null, Region.SEOUL, "KOREAN", "createdAt,desc", 0, 20)).storeIds();
        PageSnapshot secondPage = snapshot(query(
                null, Region.SEOUL, "KOREAN", "name,asc", 1, 2));

        // then
        assertThat(nameAsc).containsExactly(firstSameName.getId(), secondSameName.getId(),
                laterName.getId());
        assertThat(nameDesc).containsExactly(laterName.getId(), firstSameName.getId(),
                secondSameName.getId());
        assertThat(createdAsc).containsExactly(firstSameName.getId(), secondSameName.getId(),
                laterName.getId());
        assertThat(createdDesc).containsExactly(laterName.getId(), firstSameName.getId(),
                secondSameName.getId());
        assertThat(secondPage.storeIds()).containsExactly(laterName.getId());
        assertThat(secondPage.totalElements()).isEqualTo(3);
    }

    private Store createStore(
            String name,
            Region region,
            String categoryCode,
            boolean closed
    ) {
        int sequence = SEQUENCE.incrementAndGet();
        long operatorId = operatorRepository.saveAndFlush(StoreOperatorAccount.create(
                "search-owner-" + sequence + "@example.com", "hashed", "owner" + sequence))
                .getId();
        Store store = Store.create(
                operatorId,
                String.format("%010d", sequence),
                BusinessType.CAFE,
                name,
                "검색 통합 테스트 매장",
                region,
                "테스트 주소",
                categoryCode,
                Set.of(),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 8, 2, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1");
        if (closed) {
            store.close();
        }
        return storeRepository.saveAndFlush(store);
    }

    private void publishMenu(
            Store store,
            String name,
            MenuSellingStatus sellingStatus,
            MenuVisibility visibility,
            boolean retired
    ) {
        Menu menu = Menu.create(store.getId(), content(name), store.getStoreOperatorAccountId(), NOW);
        menu.publish(NOW.plusSeconds(1));
        menu.changeSellingStatus(sellingStatus);
        menu.changeVisibility(visibility);
        if (retired) {
            menu.retire(NOW.plusSeconds(2));
        }
        menuRepository.saveAndFlush(menu);
    }

    private void createDraftMenu(Store store, String name) {
        menuRepository.saveAndFlush(Menu.create(
                store.getId(), content(name), store.getStoreOperatorAccountId(), NOW));
    }

    private void replacePublishedMenu(Store store, String previousName, String currentName) {
        Menu menu = Menu.create(
                store.getId(), content(previousName), store.getStoreOperatorAccountId(), NOW);
        menu.publish(NOW.plusSeconds(1));
        menu.appendDraft(
                content(currentName), store.getStoreOperatorAccountId(), NOW.plusSeconds(2));
        menu.publish(NOW.plusSeconds(3));
        menuRepository.saveAndFlush(menu);
    }

    private MenuContent content(String name) {
        return new MenuContent(
                name,
                "",
                5_000,
                true,
                "BEVERAGE",
                List.of(),
                List.of(),
                true,
                true,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new OriginDisclosure("원두", "콜롬비아")),
                false);
    }

    private StoreSearchQuery query(
            String keyword,
            Region region,
            String categoryCode,
            String sort,
            int page,
            int size
    ) {
        return StoreSearchQuery.from(
                keyword, region, categoryCode, null, null, null, false, sort, page, size);
    }

    private PageSnapshot snapshot(StoreSearchQuery query) {
        var page = repository.search(query);
        return new PageSnapshot(
                page.getContent().stream().map(StoreSearchCandidate::storeId).toList(),
                page.getTotalElements());
    }

    private void setCreatedAt(Store store, String createdAt) {
        jdbcTemplate.update(
                "UPDATE stores SET created_at = ? WHERE store_id = ?", createdAt, store.getId());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private record PageSnapshot(List<Long> storeIds, long totalElements) {
    }
}
