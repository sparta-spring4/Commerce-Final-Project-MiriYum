package com.miriyum.domain.search.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationSearchAvailabilityService;
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
import com.miriyum.domain.search.interpreter.InterpretationResult;
import com.miriyum.domain.search.interpreter.InterpretedSearchCondition;
import com.miriyum.domain.search.interpreter.PriceRange;
import com.miriyum.domain.search.geo.BoundingBox;
import com.miriyum.domain.search.query.IntegratedSearchCursorCodec;
import com.miriyum.domain.search.query.IntegratedStoreSearchQuery;
import com.miriyum.domain.search.service.IntegratedSearchInterpreter;
import com.miriyum.domain.search.service.IntegratedStoreSearchService;
import com.miriyum.domain.search.semantic.SemanticMenuHit;
import com.miriyum.domain.storeoperator.dto.auth.StoreOperatorSignUpRequest;
import com.miriyum.domain.storeoperator.service.StoreOperatorAuthService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-c")
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
class IntegratedStoreSearchRepositoryIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private IntegratedStoreSearchRepository repository;

    @Autowired
    private MenuAlternativeCandidateRepository alternativeCandidateRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private StoreOperatorAuthService storeOperatorAuthService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegratedStoreSearchService integratedStoreSearchService;

    @Autowired
    private IntegratedSearchCursorCodec cursorCodec;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private IntegratedSearchInterpreter integratedSearchInterpreter;

    @MockitoBean
    private ReservationSearchAvailabilityService reservationService;

    private Long operatorId;

    @BeforeEach
    void resetOperatorFixture() {
        operatorId = null;
    }

    @Test
    @Transactional
    void combinesDifferentTypesWithAndAndSameTypeValuesWithOr() {
        Store korean = createStore(
                "서울 한식", Region.SEOUL, "KOREAN", Set.of("QUIET"), false);
        publishMenu(korean, "특선 라떼", 15_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store cafe = createStore(
                "부산 카페", Region.BUSAN, "CAFE_BAKERY", Set.of("DATE"), false);
        publishMenu(cafe, "부산 라떼", 19_000, "DESSERT", List.of("BEVERAGE"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store overPrice = createStore(
                "서울 고가 카페", Region.SEOUL, "CAFE_BAKERY", Set.of("DATE"), false);
        publishMenu(overPrice, "고가 라떼", 21_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        createStore("태그 불일치", Region.SEOUL, "KOREAN", Set.of(), false);
        flushAndClear();

        InterpretedSearchCondition condition = condition(
                List.of("SEOUL", "BUSAN"),
                List.of("KOREAN", "CAFE_BAKERY"),
                List.of("BEVERAGE"),
                List.of("QUIET", "DATE"),
                new PriceRange(10_000L, 20_000L),
                "라떼");

        IntegratedStoreSearchSlice result = repository.search(query(condition, null, null, 20));

        assertThat(ids(result)).containsExactly(cafe.getId(), korean.getId());
    }

    @Test
    @Transactional
    void exposesCurrentPublishedVisibleMenusRegardlessOfSellingStatus() {
        Store selling = storeWithMenu(
                "판매 매장", "상태키워드", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        Store soldOut = storeWithMenu(
                "품절 매장", "상태키워드", MenuSellingStatus.SOLD_OUT,
                MenuVisibility.VISIBLE, false);
        Store paused = storeWithMenu(
                "중지 매장", "상태키워드", MenuSellingStatus.PAUSED,
                MenuVisibility.VISIBLE, false);
        storeWithMenu("숨김 매장", "상태키워드", MenuSellingStatus.SELLING,
                MenuVisibility.HIDDEN, false);
        storeWithMenu("은퇴 매장", "상태키워드", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, true);
        Store closed = storeWithMenu(
                "폐점 매장", "상태키워드", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        closed.close();
        storeRepository.saveAndFlush(closed);
        flushAndClear();

        IntegratedStoreSearchSlice result = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null, "상태키워드"),
                null, null, 20));

        assertThat(ids(result)).containsExactlyInAnyOrder(
                selling.getId(), soldOut.getId(), paused.getId());
    }

    @Test
    @Transactional
    void unavailableMenuRemainsAVisibleSourceButNeverBecomesAnAlternativeCandidate() {
        Store store = createStore(
                "대안 검색 매장", Region.SEOUL, "CAFE_BAKERY", Set.of(), false);
        Menu soldOutSource = publishMenu(
                store, "품절 원본", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SOLD_OUT, MenuVisibility.VISIBLE, false);
        Menu pausedSource = publishMenu(
                store, "판매 중단 원본", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.PAUSED, MenuVisibility.VISIBLE, false);
        Menu sellingCandidate = publishMenu(
                store, "판매 후보", 11_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Menu soldOutCandidate = publishMenu(
                store, "품절 후보", 12_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SOLD_OUT, MenuVisibility.VISIBLE, false);
        flushAndClear();

        assertThat(alternativeCandidateRepository.findSource(
                store.getId(), soldOutSource.getId()))
                .get()
                .extracting(source -> source.menuId())
                .isEqualTo(soldOutSource.getId());
        assertThat(alternativeCandidateRepository.findSource(
                store.getId(), pausedSource.getId()))
                .get()
                .extracting(source -> source.menuId())
                .isEqualTo(pausedSource.getId());
        assertThat(alternativeCandidateRepository.findSameStoreCandidates(
                store.getId(), soldOutSource.getId(), 20))
                .extracting(candidate -> candidate.menuId())
                .containsExactly(sellingCandidate.getId())
                .doesNotContain(soldOutCandidate.getId());
    }

    @Test
    @Transactional
    void semanticHitsAreRevalidatedAgainstCurrentPublishedMenuVersion() {
        Store store = createStore(
                "의미 검색 매장", Region.SEOUL, "KOREAN", Set.of(), false);
        Menu menu = publishMenu(
                store, "김치찌개", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        int currentVersion = menu.getPublishedVersionNumber();
        flushAndClear();
        IntegratedStoreSearchQuery query = query(condition(
                List.of("SEOUL"), List.of(), List.of(), List.of(), null,
                "얼큰한 국물"), null, null, 20);

        IntegratedStoreSearchSlice current = repository.searchSemantic(
                query, List.of(new SemanticMenuHit(
                        menu.getId(), store.getId(), currentVersion, 0.91)));
        IntegratedStoreSearchSlice stale = repository.searchSemantic(
                query, List.of(new SemanticMenuHit(
                        menu.getId(), store.getId(), currentVersion + 1, 0.99)));

        assertThat(ids(current)).containsExactly(store.getId());
        assertThat(stale.content()).isEmpty();
        assertThat(alternativeCandidateRepository.findCurrent(menu.getId()))
                .get()
                .satisfies(document -> {
                    assertThat(document.storeId()).isEqualTo(store.getId());
                    assertThat(document.versionNumber()).isEqualTo(currentVersion);
                    assertThat(document.text()).contains("김치찌개", "BEVERAGE");
                });
    }

    @Test
    @Transactional
    void semanticAlternativeHitsRequireCurrentStoreAndPublishedVersionCoordinates() {
        Store sourceStore = createStore(
                "대체 원본 매장", Region.SEOUL, "KOREAN", Set.of(), false);
        Menu source = publishMenu(
                sourceStore, "원본 메뉴", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SOLD_OUT, MenuVisibility.VISIBLE, false);
        Menu sameStoreCandidate = publishMenu(
                sourceStore, "동일 매장 후보", 11_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store nearbyStore = createStore(
                "인근 후보 매장", Region.SEOUL, "KOREAN", Set.of(), false);
        setVerifiedCoordinates(sourceStore, "37.566500000000000", "126.978000000000000");
        setVerifiedCoordinates(nearbyStore, "37.566600000000000", "126.978100000000000");
        Menu nearbyCandidate = publishMenu(
                nearbyStore, "인근 매장 후보", 12_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        flushAndClear();
        BoundingBox box = new BoundingBox(37.5, 37.6, 126.9, 127.1);

        assertThat(alternativeCandidateRepository.findSameStoreSemanticCandidates(
                sourceStore.getId(), source.getId(), List.of(new SemanticMenuHit(
                        sameStoreCandidate.getId(), sourceStore.getId(),
                        sameStoreCandidate.getPublishedVersionNumber() + 1, 0.99)), 20))
                .isEmpty();
        assertThat(alternativeCandidateRepository.findNearbySemanticCandidates(
                sourceStore.getId(), source.getId(), box, List.of(new SemanticMenuHit(
                        nearbyCandidate.getId(), nearbyStore.getId() + 1,
                        nearbyCandidate.getPublishedVersionNumber(), 0.99)), 20))
                .isEmpty();
    }

    @Test
    @Transactional
    void treatsLikeMetaCharactersAsLiteralKeywordText() {
        Store literal = storeWithMenu(
                "리터럴", "100%_특선!", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        storeWithMenu("와일드카드 유사", "100ABC특선!", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        flushAndClear();

        IntegratedStoreSearchSlice result = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null, "100%_특선!"),
                null, null, 20));

        assertThat(ids(result)).containsExactly(literal.getId());
    }

    @Test
    @Transactional
    void searchesTheLocalizedRegionNameAsGeneralKeywordFallback() {
        Store seoul = createStore(
                "지역 fallback 대상", Region.SEOUL, "KOREAN", Set.of(), false);
        createStore("다른 지역", Region.BUSAN, "KOREAN", Set.of(), false);
        flushAndClear();

        IntegratedStoreSearchSlice result = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null, "서울"),
                null, null, 20));

        assertThat(ids(result)).containsExactly(seoul.getId());
    }

    @Test
    @Transactional
    void handlesPriceBoundsBeyondTheIntegerMenuColumnWithoutOverflow() {
        Store store = storeWithMenu(
                "가격 경계", "가격 메뉴", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        flushAndClear();

        IntegratedStoreSearchSlice unboundedMaximum = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(),
                        new PriceRange(0L, Long.MAX_VALUE), ""),
                null, null, 20));
        IntegratedStoreSearchSlice impossibleMinimum = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(),
                        new PriceRange(Long.MAX_VALUE, null), ""),
                null, null, 20));

        assertThat(ids(unboundedMaximum)).contains(store.getId());
        assertThat(ids(impossibleMinimum)).isEmpty();
    }

    @Test
    @Transactional
    void keysetCursorReturnsEveryTieExactlyOnceForAllSorts() {
        Store first = createStore("동일", Region.SEOUL, "KOREAN", Set.of(), false);
        Store second = createStore("동일", Region.SEOUL, "KOREAN", Set.of(), false);
        Store third = createStore("후순위", Region.SEOUL, "KOREAN", Set.of(), false);
        setCreatedAt(first, "2026-08-01 09:00:00");
        setCreatedAt(second, "2026-08-01 09:00:00");
        setCreatedAt(third, "2026-08-02 09:00:00");
        flushAndClear();

        for (String sort : List.of(
                "name,asc", "name,desc", "createdAt,asc", "createdAt,desc")) {
            List<Long> actual = new ArrayList<>();
            String cursor = null;
            do {
                IntegratedStoreSearchSlice page = repository.search(query(
                        condition(), sort, cursor, 1));
                actual.addAll(ids(page));
                cursor = page.nextCursor();
            } while (cursor != null);

            List<Long> expected = switch (sort) {
                case "name,asc", "createdAt,asc" ->
                        List.of(first.getId(), second.getId(), third.getId());
                case "name,desc", "createdAt,desc" ->
                        List.of(third.getId(), first.getId(), second.getId());
                default -> throw new IllegalStateException("unexpected test sort");
            };
            assertThat(actual).as(sort).containsExactlyElementsOf(expected);
            assertThat(actual).doesNotHaveDuplicates();
        }
    }

    @Test
    @Transactional
    void ordersKeywordMatchesByFixedRelevanceAndReturnsOnlyCurrentVerifiedCoordinates() {
        Store exact = createStore("라떼", Region.SEOUL, "KOREAN", Set.of(), false);
        Store storeName = createStore(
                "라떼 전문점", Region.SEOUL, "KOREAN", Set.of(), false);
        Store menuName = storeWithMenu(
                "메뉴 매장", "라떼", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        Store address = createStore(
                "주소 매장", Region.SEOUL, "KOREAN", Set.of(), false);
        setAddress(address, "라떼 거리");
        setVerifiedCoordinates(exact, "37.566500000000000", "126.978000000000000");
        flushAndClear();

        IntegratedStoreSearchSlice result = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null, "라떼"),
                "relevance,desc", null, 20));

        assertThat(result.content())
                .extracting(IntegratedStoreSearchCandidate::name)
                .containsExactly("라떼", "라떼 전문점", "메뉴 매장", "주소 매장");
        assertThat(result.content().getFirst().latitude())
                .isEqualByComparingTo("37.566500000000000");
        assertThat(result.content().getFirst().longitude())
                .isEqualByComparingTo("126.978000000000000");
        assertThat(result.content().get(1).latitude()).isNull();
        assertThat(result.content().get(1).longitude()).isNull();
    }

    @Test
    @Transactional
    void refreshesCurrentModesAndRemovesStoresThatAreNoLongerPublic() {
        Store modeChanged = createStore(
                "모드 변경", Region.SEOUL, "KOREAN", Set.of(), false);
        Store closed = createStore(
                "폐점 전환", Region.SEOUL, "KOREAN", Set.of(), false);
        flushAndClear();
        List<IntegratedStoreSearchCandidate> candidates = repository.search(query(
                condition(), "name,asc", null, 20)).content();

        Store currentModeChanged = storeRepository.findById(modeChanged.getId()).orElseThrow();
        currentModeChanged.update(
                null, null, null, null, null, null,
                false, null, null, null);
        Store currentClosed = storeRepository.findById(closed.getId()).orElseThrow();
        currentClosed.close();
        storeRepository.saveAllAndFlush(List.of(currentModeChanged, currentClosed));
        entityManager.clear();

        List<IntegratedStoreSearchCandidate> refreshed =
                repository.refreshCurrentlyPublic(candidates);

        assertThat(refreshed).singleElement().satisfies(candidate -> {
            assertThat(candidate.storeId()).isEqualTo(modeChanged.getId());
            assertThat(candidate.reservationEnabled()).isFalse();
        });
    }

    @Test
    void finalRefreshSeesAStoreClosedByASeparatelyCommittedTransaction() {
        Store target = createStore(
                "동시종료-검색대상", Region.SEOUL, "KOREAN", Set.of(), false);
        InterpretedSearchCondition interpreted = new InterpretedSearchCondition(
                List.of(), List.of(), List.of(), List.of(), null,
                2, java.time.LocalDate.of(2026, 8, 8),
                java.time.LocalTime.of(18, 0), "동시종료-검색대상");
        given(integratedSearchInterpreter.interpret("동시 종료 예약"))
                .willReturn(new InterpretationResult(
                        "rule-v1", "catalog-v1", interpreted, List.of()));
        given(reservationService.getAvailabilities(any(), any())).willAnswer(invocation -> {
            TransactionTemplate separate = new TransactionTemplate(transactionManager);
            separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            separate.executeWithoutResult(status -> jdbcTemplate.update(
                    "UPDATE stores SET operation_status = 'CLOSED' WHERE store_id = ?",
                    target.getId()));
            List<Long> requested = invocation.getArgument(0);
            return requested.stream()
                    .map(storeId -> new ReservationAvailabilityResult(
                            storeId, ReservationAvailabilityStatus.AVAILABLE))
                    .toList();
        });

        var result = integratedStoreSearchService.search(
                "동시 종료 예약", false, false, null, null, 20);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void publicSearchIndexesRemainAvailableForQuerydslPredicates() {
        assertThat(indexColumns("stores", "idx_stores_public_search"))
                .containsExactly("verification_status", "name", "store_id");
        assertThat(indexColumns("menus", "idx_menus_public_search"))
                .containsExactly("store_id", "retired", "visibility", "published_version_number");
    }

    private Store storeWithMenu(
            String storeName,
            String menuName,
            MenuSellingStatus sellingStatus,
            MenuVisibility visibility,
            boolean retired
    ) {
        Store store = createStore(
                storeName, Region.SEOUL, "CAFE_BAKERY", Set.of(), false);
        publishMenu(store, menuName, 10_000, "BEVERAGE", List.of(),
                sellingStatus, visibility, retired);
        return store;
    }

    private Store createStore(
            String name,
            Region region,
            String categoryCode,
            Set<String> tags,
        boolean closed
    ) {
        int sequence = SEQUENCE.incrementAndGet();
        long fixtureOperatorId = operatorId == null ? createOperator() : operatorId;
        Store store = Store.create(
                fixtureOperatorId,
                String.format("%010d", 8_000_000 + sequence),
                BusinessType.CAFE,
                name,
                "QueryDSL 통합 테스트 매장",
                region,
                "테스트 주소",
                categoryCode,
                tags,
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 8, 5, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1");
        if (closed) {
            store.close();
        }
        return storeRepository.saveAndFlush(store);
    }

    private long createOperator() {
        int sequence = SEQUENCE.incrementAndGet();
        long createdId = Long.parseLong(storeOperatorAuthService.signUp(
                new StoreOperatorSignUpRequest(
                        "querydsl-owner-" + sequence + "@example.com",
                        "Password123!",
                        "Password123!",
                        String.format("010-1000-%04d", sequence),
                        "QueryDSL 운영자")).accountId());
        operatorId = createdId;
        return createdId;
    }

    private Menu publishMenu(
            Store store,
            String name,
            int price,
            String primaryCategory,
            List<String> secondaryCategories,
            MenuSellingStatus sellingStatus,
            MenuVisibility visibility,
            boolean retired
    ) {
        MenuContent content = new MenuContent(
                name,
                "",
                price,
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

    private IntegratedStoreSearchQuery query(
            InterpretedSearchCondition condition,
            String sort,
            String cursor,
            Integer size
    ) {
        return IntegratedStoreSearchQuery.from(
                condition, sort, cursor, size, cursorCodec);
    }

    private InterpretedSearchCondition condition() {
        return condition(List.of(), List.of(), List.of(), List.of(), null, "");
    }

    private InterpretedSearchCondition condition(
            List<String> regions,
            List<String> storeCategories,
            List<String> menuCategories,
            List<String> tags,
            PriceRange priceRange,
            String keyword
    ) {
        return new InterpretedSearchCondition(
                regions, storeCategories, menuCategories, tags, priceRange,
                null, null, null, keyword);
    }

    private List<Long> ids(IntegratedStoreSearchSlice slice) {
        return slice.content().stream()
                .map(IntegratedStoreSearchCandidate::storeId)
                .toList();
    }

    private void setAddress(Store store, String address) {
        jdbcTemplate.update(
                "UPDATE stores SET address = ? WHERE store_id = ?", address, store.getId());
    }

    private void setVerifiedCoordinates(
            Store store,
            String latitude,
            String longitude
    ) {
        jdbcTemplate.update("""
                UPDATE stores
                SET geocoding_status = 'VERIFIED',
                    latitude = ?,
                    longitude = ?,
                    verified_address = address,
                    geocoding_verified_at = '2026-08-06 00:00:00',
                    geocoding_address_version = address_version
                WHERE store_id = ?
                """, latitude, longitude, store.getId());
    }

    private void setCreatedAt(Store store, String createdAt) {
        jdbcTemplate.update(
                "UPDATE stores SET created_at = ? WHERE store_id = ?", createdAt, store.getId());
    }

    private List<String> indexColumns(String tableName, String indexName) {
        return jdbcTemplate.query("""
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                ORDER BY seq_in_index
                """, (resultSet, rowNumber) -> resultSet.getString("column_name"),
                tableName, indexName);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
