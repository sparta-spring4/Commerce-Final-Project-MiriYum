package com.miriyum.domain.store.search.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
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
import com.miriyum.domain.store.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.store.search.model.StoreSearchQuery;
import com.miriyum.domain.store.search.service.StoreSearchCatalogPolicy;
import com.miriyum.domain.store.search.service.StoreSearchCoreService;
import com.miriyum.domain.store.service.CatalogService;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.menu.schedule.enabled=false",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "spring.task.scheduling.enabled=false"
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
    private StorePublicReadRepository publicReadRepository;

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

    @Test
    @DisplayName("공개 매장 검색의 모든 정렬 계약에 맞는 방향별 인덱스를 설치한다")
    void installsPublicSearchIndexesInColumnOrder() {
        // when
        List<String> nameAscIndex = indexColumnsWithDirection("idx_stores_public_search");
        List<String> nameDescIndex = indexColumnsWithDirection(
                "idx_stores_public_search_name_desc");
        List<String> createdAtAscIndex = indexColumnsWithDirection(
                "idx_stores_public_search_created_at_asc");
        List<String> createdAtDescIndex = indexColumnsWithDirection(
                "idx_stores_public_search_created_at_desc");
        List<String> menuIndexColumns = indexColumns("idx_menus_public_search");

        // then
        assertThat(nameAscIndex).containsExactly(
                "verification_status:ASC", "name:ASC", "store_id:ASC");
        assertThat(nameDescIndex).containsExactly(
                "verification_status:ASC", "name:DESC", "store_id:ASC");
        assertThat(createdAtAscIndex).containsExactly(
                "verification_status:ASC", "created_at:ASC", "store_id:ASC");
        assertThat(createdAtDescIndex).containsExactly(
                "verification_status:ASC", "created_at:DESC", "store_id:ASC");
        assertThat(menuIndexColumns)
                .containsExactly(
                        "store_id", "retired", "visibility", "published_version_number");
    }

    @Test
    @DisplayName("현재 공개 메뉴 조회 실행계획에서 복합 인덱스를 사용한다")
    void usesPublicMenuSearchIndexForCurrentPublishedLookup() {
        // given
        Store targetStore = createStore("실행계획 대상", Region.SEOUL, "KOREAN", false);
        try {
            String insertSql = """
                    INSERT INTO menus (
                        store_id, next_version_number, draft_version_number,
                        scheduled_version_number, published_version_number,
                        visibility, selling_status, retired, lock_version, created_at, updated_at
                    ) VALUES (?, 2, NULL, NULL, 1, ?, 'SELLING', FALSE, 0, NOW(6), NOW(6))
                    """;
            List<Object[]> nonMatchingMenus = IntStream.range(0, 100)
                    .mapToObj(index -> new Object[]{targetStore.getId(), "HIDDEN"})
                    .toList();
            jdbcTemplate.batchUpdate(insertSql, nonMatchingMenus);
            jdbcTemplate.update(insertSql, targetStore.getId(), "VISIBLE");
            jdbcTemplate.execute("ANALYZE TABLE menus");

            // when
            String executionPlan = jdbcTemplate.queryForObject("""
                    EXPLAIN FORMAT=JSON
                    SELECT m.published_version_number
                    FROM menus m
                    WHERE m.store_id = ?
                      AND m.retired = FALSE
                      AND m.visibility = 'VISIBLE'
                      AND m.published_version_number IS NOT NULL
                    """, String.class, targetStore.getId());

            // then
            assertThat(executionPlan)
                    .contains("\"key\": \"idx_menus_public_search\"");
        } finally {
            jdbcTemplate.update("DELETE FROM menus WHERE store_id = ?", targetStore.getId());
            jdbcTemplate.update("DELETE FROM stores WHERE store_id = ?", targetStore.getId());
            jdbcTemplate.update(
                    "DELETE FROM store_operator_accounts WHERE store_operator_account_id = ?",
                    targetStore.getStoreOperatorAccountId());
        }
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

    @Test
    @Transactional
    void searchAllStopsAtTheConfiguredCandidateLimitInStableOrder() {
        Store first = createStore("후보상한 가", Region.SEOUL, "KOREAN", false);
        Store second = createStore("후보상한 나", Region.SEOUL, "KOREAN", false);
        createStore("후보상한 다", Region.SEOUL, "KOREAN", false);
        flushAndClear();

        List<Long> result = repository.searchAll(
                        query("후보상한", null, null, "name,asc", 0, 20), 2).stream()
                .map(StoreSearchCandidate::storeId)
                .toList();

        assertThat(result).containsExactly(first.getId(), second.getId());
    }

    @Test
    @Transactional
    void retainCurrentlyPublicPreservesInputOrderAndDuplicates() {
        Store open = createStore("공개 매장", Region.SEOUL, "KOREAN", false);
        Store closed = createStore("폐점 매장", Region.SEOUL, "KOREAN", false);
        closed.close();
        storeRepository.saveAndFlush(closed);
        flushAndClear();

        List<Long> result = repository.retainCurrentlyPublic(
                List.of(closed.getId(), open.getId(), open.getId()));

        assertThat(result).containsExactly(open.getId(), open.getId());
    }

    @Test
    @Transactional
    void refreshCurrentlyPublicPreservesCandidateFieldsAndReadsLatestSafetyState() {
        Store changed = createStore("변경 전", Region.SEOUL, "KOREAN", false);
        Store closed = createStore("곧 폐점", Region.SEOUL, "KOREAN", false);
        flushAndClear();
        List<StoreSearchCandidate> candidates = repository.searchAll(
                query(null, null, null, "name,asc", 0, 20), 100);
        StoreSearchCandidate changedBefore = candidates.stream()
                .filter(candidate -> candidate.storeId() == changed.getId()).findFirst().orElseThrow();
        StoreSearchCandidate closedBefore = candidates.stream()
                .filter(candidate -> candidate.storeId() == closed.getId()).findFirst().orElseThrow();
        jdbcTemplate.update("""
                UPDATE stores
                SET name = '변경 후', region = 'BUSAN', address = '변경 주소',
                    store_category_code = 'CAFE_BAKERY',
                    operation_status = 'TEMPORARILY_CLOSED',
                    reservation_enabled = FALSE, menu_hold_enabled = FALSE,
                    pickup_enabled = FALSE
                WHERE store_id = ?
                """, changed.getId());
        jdbcTemplate.update("UPDATE stores SET operation_status = 'CLOSED' WHERE store_id = ?",
                closed.getId());

        List<StoreSearchCandidate> refreshed = repository.refreshCurrentlyPublic(
                List.of(closedBefore, changedBefore));

        assertThat(refreshed).singleElement().satisfies(current -> {
            assertThat(current.storeId()).isEqualTo(changed.getId());
            assertThat(current.name()).isEqualTo("변경 전");
            assertThat(current.region()).isEqualTo(Region.SEOUL);
            assertThat(current.address()).isEqualTo("테스트 주소");
            assertThat(current.storeCategoryCode()).isEqualTo("KOREAN");
            assertThat(current.operationStatus()).isEqualTo(OperationStatus.TEMPORARILY_CLOSED);
            assertThat(current.reservationEnabled()).isFalse();
            assertThat(current.menuHoldEnabled()).isFalse();
            assertThat(current.pickupEnabled()).isFalse();
        });
    }

    @Test
    @Transactional
    void refreshCurrentlyPublicPreservesRegionAfterConcurrentRegionChange() {
        Store moved = createStore("지역 이동", Region.SEOUL, "KOREAN", false);
        flushAndClear();
        StoreSearchCandidate candidate = repository.search(
                        query(null, Region.SEOUL, null, "name,asc", 0, 20))
                .getContent()
                .getFirst();
        jdbcTemplate.update(
                "UPDATE stores SET region = 'BUSAN' WHERE store_id = ?",
                moved.getId());

        List<StoreSearchCandidate> refreshed = repository.refreshCurrentlyPublic(
                List.of(candidate));

        assertThat(refreshed).singleElement().satisfies(current -> {
            assertThat(current.storeId()).isEqualTo(moved.getId());
            assertThat(current.region()).isEqualTo(Region.SEOUL);
        });
    }

    @Test
    @Transactional
    void availableOnlyKeepsExactIdentityWhenNameChangesBetweenBatches() {
        List<Store> stores = IntStream.rangeClosed(1, 201)
                .mapToObj(index -> createStore(
                        String.format("동시성스냅샷 %03d", index),
                        Region.SEOUL,
                        "KOREAN",
                        false))
                .toList();
        flushAndClear();
        ReservationService reservationService = mock(ReservationService.class);
        StoreSearchCoreService service = new StoreSearchCoreService(
                new StoreSearchCatalogPolicy(mock(CatalogService.class)),
                repository,
                reservationService,
                new StoreSearchCandidateLimit(5_000));
        AtomicInteger availabilityCalls = new AtomicInteger();
        given(reservationService.getAvailabilities(any(), any())).willAnswer(invocation -> {
            List<Long> storeIds = invocation.getArgument(0);
            if (availabilityCalls.getAndIncrement() == 0) {
                jdbcTemplate.update(
                        "UPDATE stores SET name = ? WHERE store_id = ?",
                        "동시성스냅샷 999",
                        stores.getFirst().getId());
            }
            return storeIds.stream()
                    .map(storeId -> new ReservationAvailabilityResult(
                            storeId, ReservationAvailabilityStatus.AVAILABLE))
                    .toList();
        });
        StoreSearchQuery query = StoreSearchQuery.from(
                "동시성스냅샷",
                Region.SEOUL,
                null,
                LocalDate.of(2026, 8, 3),
                LocalTime.of(18, 0),
                2,
                true,
                "name,asc",
                2,
                100);

        var result = service.search(query, false);

        assertThat(result.getTotalElements()).isEqualTo(201);
        assertThat(result.getContent())
                .extracting(summary -> Long.parseLong(summary.storeId()))
                .containsExactly(stores.getLast().getId());
    }

    @Test
    @Transactional
    void publicReadProjectsOnlyCurrentPublishedVisibleMenus() {
        Store store = createStore("공개 상세", Region.SEOUL, "KOREAN", false);
        replacePublishedMenu(store, "과거 메뉴", "현재 메뉴");
        publishMenu(store, "숨김 메뉴", MenuSellingStatus.SELLING, MenuVisibility.HIDDEN, false);
        flushAndClear();

        var snapshot = publicReadRepository.findPublicStore(store.getId());
        var menus = publicReadRepository.findPublicMenus(store.getId());

        assertThat(snapshot).get().satisfies(found -> {
            assertThat(found.name()).isEqualTo("공개 상세");
            assertThat(found.operationStatus()).isEqualTo(OperationStatus.OPEN);
        });
        assertThat(menus).extracting(com.miriyum.domain.store.search.dto.PublicMenu::name)
                .containsExactly("현재 메뉴");
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

    private List<String> indexColumns(String indexName) {
        return jdbcTemplate.query("""
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name IN ('stores', 'menus')
                  AND index_name = ?
                ORDER BY seq_in_index
                """, (resultSet, rowNumber) -> resultSet.getString("column_name"), indexName);
    }

    private List<String> indexColumnsWithDirection(String indexName) {
        return jdbcTemplate.query("""
                SELECT CONCAT(column_name, ':',
                    CASE collation WHEN 'D' THEN 'DESC' ELSE 'ASC' END)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'stores'
                  AND index_name = ?
                ORDER BY seq_in_index
                """, (resultSet, rowNumber) -> resultSet.getString(1), indexName);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private record PageSnapshot(List<Long> storeIds, long totalElements) {
    }
}
