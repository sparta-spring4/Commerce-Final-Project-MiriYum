package com.miriyum.domain.search.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationSearchAvailabilityService;
import com.miriyum.domain.store.entity.Store;
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
import com.miriyum.domain.search.interpreter.DeterministicFoodEvidenceExtractor;
import com.miriyum.domain.search.interpreter.FoodEvidenceVocabulary;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.Dimension;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.EvidenceTerm;
import com.miriyum.domain.search.expansion.StructuredFoodEvidenceSource;
import com.miriyum.domain.search.geo.BoundingBox;
import com.miriyum.domain.search.query.IntegratedSearchCursorCodec;
import com.miriyum.domain.search.query.IntegratedStoreSearchQuery;
import com.miriyum.domain.search.service.IntegratedSearchInterpreter;
import com.miriyum.domain.search.service.IntegratedStoreSearchService;
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
    void expandedConceptsFindCurrentMenuWhilePreservingStructuredRegion() {
        Store seoulKimchi = createStore(
                "서울 김치찌개", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenu(
                seoulKimchi, "돼지고기 김치찌개", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store busanKimchi = createStore(
                "부산 김치찌개", Region.BUSAN, "KOREAN", Set.of(), false);
        publishMenu(
                busanKimchi, "김치찌개", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store closedKimchi = createStore(
                "종료 김치찌개", Region.SEOUL, "KOREAN", Set.of(), true);
        publishMenu(
                closedKimchi, "김치찌개", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        storeWithMenu(
                "서울 디저트", "딸기 케이크", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        flushAndClear();
        IntegratedStoreSearchQuery query = query(condition(
                List.of("SEOUL"), List.of(), List.of(), List.of(), null,
                "얼큰한 국물"), null, null, 20);

        List<IntegratedStoreSearchCandidate> result = repository.searchExpanded(
                query, List.of("김치찌개", "찌개"), 200);

        assertThat(result).extracting(IntegratedStoreSearchCandidate::storeId)
                .containsExactly(seoulKimchi.getId());
    }

    @Test
    @Transactional
    void compoundConceptsMatchContainedCurrentMenuNames() {
        Store jeyuk = storeWithMenu(
                "제육 매장", "제육볶음", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        Store dakgalbi = storeWithMenu(
                "닭갈비 매장", "닭갈비", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        Store jjambbong = storeWithMenu(
                "짬뽕 매장", "짬뽕", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        Store pho = storeWithMenu(
                "쌀국수 매장", "쌀국수", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        Store bulgogi = storeWithMenu(
                "불고기 매장", "불고기", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        Store americano = storeWithMenu(
                "커피 매장", "아메리카노", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        flushAndClear();

        List<IntegratedStoreSearchCandidate> result = repository.searchExpanded(
                query(condition(), null, null, 20),
                List.of(
                        "매운 제육볶음",
                        "매운닭갈비",
                        "불향 해물 짬뽕",
                        "소고기 쌀국수",
                        "소고기 불고기",
                        "아이스 아메리카노"),
                200);

        assertThat(result).extracting(IntegratedStoreSearchCandidate::storeId)
                .containsExactlyInAnyOrder(
                        jeyuk.getId(),
                        dakgalbi.getId(),
                        jjambbong.getId(),
                        pho.getId(),
                        bulgogi.getId(),
                        americano.getId());
        assertThat(result).allMatch(candidate -> candidate.relevanceTier() == 0);
    }

    @Test
    @Transactional
    void reverseNameMatchingRejectsGenericUnrelatedWildcardAndNonNameFields() {
        for (String genericName : List.of(
                "면", "탕", "국", "밥", "메뉴", "음식", "요리", "식사", "세트", "정식", "음료")) {
            storeWithMenu(
                    genericName + " 매장", genericName, MenuSellingStatus.SELLING,
                    MenuVisibility.VISIBLE, false);
        }
        storeWithMenu(
                "무관 메뉴 매장", "초밥", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        storeWithMenu(
                "와일드카드 유사 매장", "ABC특선", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        Store nonNameFields = createStore(
                "비메뉴명 필드 매장", Region.SEOUL, "CAFE_BAKERY", Set.of(), false);
        publishMenuWithSearchFields(
                nonNameFields,
                "완전히 다른 이름",
                "짬뽕",
                10_000,
                "BEVERAGE",
                List.of("DESSERT"),
                List.of("매운맛"),
                MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE,
                false);
        flushAndClear();

        List<IntegratedStoreSearchCandidate> result = repository.searchExpanded(
                query(condition(), null, null, 20),
                List.of(
                        "따뜻한 면", "얼큰한 탕", "맑은 국", "고기 밥",
                        "추천 메뉴", "맛있는 음식", "한국 요리", "저녁 식사",
                        "가족 세트", "점심 정식", "차가운 음료",
                        "매운 제육볶음", "100%_특선!",
                        "불향 해물 짬뽕", "따뜻한 BEVERAGE",
                        "달콤한 DESSERT", "아주 매운맛"),
                200);

        assertThat(result).isEmpty();
    }

    @Test
    @Transactional
    void reverseNameMatchingPreservesCurrentStateAndStructuredFilters() {
        Store target = createStore(
                "대상 매장", Region.SEOUL, "KOREAN", Set.of("QUIET"), false);
        publishMenu(target, "짬뽕", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);

        Store hidden = createStore(
                "숨김 매장", Region.SEOUL, "KOREAN", Set.of("QUIET"), false);
        publishMenu(hidden, "짬뽕", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.HIDDEN, false);
        Store retired = createStore(
                "은퇴 매장", Region.SEOUL, "KOREAN", Set.of("QUIET"), false);
        publishMenu(retired, "짬뽕", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, true);
        Store closed = createStore(
                "폐점 매장", Region.SEOUL, "KOREAN", Set.of("QUIET"), true);
        publishMenu(closed, "짬뽕", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store wrongRegion = createStore(
                "부산 매장", Region.BUSAN, "KOREAN", Set.of("QUIET"), false);
        publishMenu(wrongRegion, "짬뽕", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store wrongStoreCategory = createStore(
                "업종 불일치", Region.SEOUL, "CAFE_BAKERY", Set.of("QUIET"), false);
        publishMenu(wrongStoreCategory, "짬뽕", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store wrongTag = createStore(
                "태그 불일치", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenu(wrongTag, "짬뽕", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store wrongMenuCategory = createStore(
                "메뉴 분류 불일치", Region.SEOUL, "KOREAN", Set.of("QUIET"), false);
        publishMenu(wrongMenuCategory, "짬뽕", 10_000, "DESSERT", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store overPrice = createStore(
                "가격 불일치", Region.SEOUL, "KOREAN", Set.of("QUIET"), false);
        publishMenu(overPrice, "짬뽕", 20_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store pastVersion = createStore(
                "과거 버전 매장", Region.SEOUL, "KOREAN", Set.of("QUIET"), false);
        Menu versioned = publishMenu(
                pastVersion, "짬뽕", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        versioned.appendDraft(
                menuContent("우동", "", 10_000, "BEVERAGE", List.of(), List.of()),
                pastVersion.getStoreOperatorAccountId(), NOW.plusSeconds(2));
        versioned.publish(NOW.plusSeconds(3));
        menuRepository.saveAndFlush(versioned);
        flushAndClear();

        IntegratedStoreSearchQuery query = query(condition(
                List.of("SEOUL"),
                List.of("KOREAN"),
                List.of("BEVERAGE"),
                List.of("QUIET"),
                new PriceRange(9_000L, 11_000L),
                ""), null, null, 20);
        List<IntegratedStoreSearchCandidate> result = repository.searchExpanded(
                query, List.of("불향 해물 짬뽕"), 200);

        assertThat(result).extracting(IntegratedStoreSearchCandidate::storeId)
                .containsExactly(target.getId());
    }

    @Test
    @Transactional
    void forwardExpandedMatchesPrecedeReverseMatchesAndRemoveDuplicateStores() {
        Store reverse = storeWithMenu(
                "가 역방향", "제육볶음", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        Store forward = storeWithMenu(
                "나 정방향", "매운 제육볶음 정식", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        Store duplicate = createStore(
                "다 중복", Region.SEOUL, "CAFE_BAKERY", Set.of(), false);
        publishMenu(duplicate, "매운 제육볶음", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        publishMenu(duplicate, "제육볶음", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        flushAndClear();

        List<IntegratedStoreSearchCandidate> result = repository.searchExpanded(
                query(condition(), null, null, 20), List.of("매운 제육볶음"), 200);

        assertThat(result).extracting(IntegratedStoreSearchCandidate::storeId)
                .containsExactly(forward.getId(), duplicate.getId(), reverse.getId());
        assertThat(result).extracting(IntegratedStoreSearchCandidate::relevanceTier)
                .containsExactly(1, 1, 0);
        assertThat(result).extracting(IntegratedStoreSearchCandidate::storeId)
                .doesNotHaveDuplicates();
    }

    @Test
    @Transactional
    void expandedAlternativeCandidatesUseCurrentSellingMenusInSameAndNearbyStores() {
        Store sourceStore = createStore(
                "대체 원본 매장", Region.SEOUL, "KOREAN", Set.of(), false);
        Menu source = publishMenu(
                sourceStore, "원본 메뉴", 10_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SOLD_OUT, MenuVisibility.VISIBLE, false);
        Menu sameStoreCandidate = publishMenu(
                sourceStore, "동일 매장 후보", 11_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Menu secondaryConceptCandidate = publishMenu(
                sourceStore, "보조 분류 후보", 11_000, "BEVERAGE", List.of("DESSERT"),
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

        assertThat(alternativeCandidateRepository.findSameStoreExpandedCandidates(
                sourceStore.getId(), source.getId(),
                List.of("동일 매장 후보", "DESSERT"), 20))
                .satisfiesExactly(candidate -> {
                    assertThat(candidate.menuId()).isEqualTo(sameStoreCandidate.getId());
                    assertThat(candidate.conceptScore()).isEqualTo(50);
                }, candidate -> {
                    assertThat(candidate.menuId()).isEqualTo(secondaryConceptCandidate.getId());
                    assertThat(candidate.conceptScore()).isEqualTo(45);
                });
        assertThat(alternativeCandidateRepository.findNearbyExpandedCandidates(
                sourceStore.getId(), source.getId(), box,
                List.of("인근 매장 후보"), 20))
                .extracting(candidate -> candidate.menuId())
                .containsExactly(nearbyCandidate.getId());
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
    void originalSearchFindsExplicitMenuInsideNaturalLanguageAndRejectsGenericMenuName() {
        Store jjambbong = storeWithMenu(
                "짬뽕 매장", "짬뽕", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        storeWithMenu(
                "일반 탕 매장", "탕", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        storeWithMenu(
                "비공개 칼칼한 짬뽕", "칼칼한 짬뽕", MenuSellingStatus.SELLING,
                MenuVisibility.HIDDEN, false);
        storeWithMenu(
                "retired 옛날 짬뽕", "옛날 짬뽕", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, true);
        flushAndClear();

        String explicitKeyword = "짬뽕 파는 매장 중 추천순으로 보여줘";
        IntegratedStoreSearchSlice explicit = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null,
                        explicitKeyword),
                repository.resolveMostSpecificPublishedMenuNames(explicitKeyword),
                "relevance,desc", null, 20));
        String genericKeyword = "얼큰한 탕 파는 매장";
        IntegratedStoreSearchSlice generic = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null,
                        genericKeyword),
                repository.resolveMostSpecificPublishedMenuNames(genericKeyword),
                "relevance,desc", null, 20));
        String specificKeyword = "칼칼한 짬뽕 파는 매장";
        IntegratedStoreSearchSlice specificUnavailable = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null,
                        specificKeyword),
                repository.resolveMostSpecificPublishedMenuNames(specificKeyword),
                "relevance,desc", null, 20));
        String retiredKeyword = "옛날 짬뽕 파는 매장";
        IntegratedStoreSearchSlice retiredLongerDoesNotSuppressCurrentShorter =
                repository.search(query(
                        condition(List.of(), List.of(), List.of(), List.of(), null,
                                retiredKeyword),
                        repository.resolveMostSpecificPublishedMenuNames(retiredKeyword),
                        "relevance,desc", null, 20));

        assertThat(explicit.content())
                .extracting(IntegratedStoreSearchCandidate::storeId)
                .containsExactly(jjambbong.getId());
        assertThat(generic.content()).isEmpty();
        assertThat(specificUnavailable.content()).isEmpty();
        assertThat(retiredLongerDoesNotSuppressCurrentShorter.content())
                .extracting(IntegratedStoreSearchCandidate::storeId)
                .containsExactly(jjambbong.getId());
    }

    @Test
    @Transactional
    void explicitMenuSpecificityUsesMysqlCollationExpansionsWithoutShortFallback() {
        storeWithMenu(
                "짧은 이름 매장", "sse", MenuSellingStatus.SELLING,
                MenuVisibility.VISIBLE, false);
        storeWithMenu(
                "숨긴 긴 이름 매장", "straße", MenuSellingStatus.SELLING,
                MenuVisibility.HIDDEN, false);
        flushAndClear();

        String keyword = "straße 파는 매장";
        List<String> resolvedNames = repository
                .resolveMostSpecificPublishedMenuNames(keyword);
        IntegratedStoreSearchSlice result = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null, keyword),
                resolvedNames,
                "relevance,desc", null, 20));

        assertThat(resolvedNames).containsExactly("straße");
        assertThat(result.content()).isEmpty();
    }

    @Test
    @Transactional
    void structuredAttributesMustMatchTwoDimensionsOnTheSameCurrentVisibleMenu() {
        Store valid = createStore(
                "같은 메뉴 근거", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                valid, "해물 전골", "칼칼한 해물 국물", 14_000,
                "BEVERAGE", List.of(), List.of("해물"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);

        Store split = createStore(
                "분리 메뉴 근거", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                split, "매운 국수", "칼칼한", 12_000,
                "BEVERAGE", List.of(), List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        publishMenuWithSearchFields(
                split, "해물 튀김", "해물", 12_000,
                "BEVERAGE", List.of(), List.of("해물"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);

        Store hidden = createStore(
                "비공개 메뉴 근거", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                hidden, "해물 전골", "칼칼한 해물", 14_000,
                "BEVERAGE", List.of(), List.of("해물"),
                MenuSellingStatus.SELLING, MenuVisibility.HIDDEN, false);

        Store retired = createStore(
                "과거 메뉴 근거", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                retired, "해물 전골", "칼칼한 해물", 14_000,
                "BEVERAGE", List.of(), List.of("해물"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, true);
        flushAndClear();

        StructuredFoodEvidence evidence = new DeterministicFoodEvidenceExtractor(
                new FoodEvidenceVocabulary()).extract("칼칼한 해물 음식");
        IntegratedStoreSearchSlice result = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null,
                        "칼칼한 해물 음식"),
                List.of(),
                evidence,
                "relevance,desc",
                null,
                20));

        assertThat(result.content())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.storeId()).isEqualTo(valid.getId());
                    assertThat(candidate.structuredRelevance()).isEqualTo(26);
                });
    }

    @Test
    @Transactional
    void structuredDimensionCountPrecedesLegacyNameRelevanceAndSeeksWithoutLoss() {
        Store threeDimensions = createStore(
                "일반 매장", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                threeDimensions, "해물 전골", "칼칼한 해물 국물", 14_000,
                "BEVERAGE", List.of(), List.of("해물", "국물"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store twoDimensionsHighLegacy = createStore(
                "칼칼한 해물 음식 전문점", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                twoDimensionsHighLegacy, "해물 볶음", "칼칼한 해물", 13_000,
                "BEVERAGE", List.of(), List.of("해물"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        flushAndClear();

        FoodEvidenceVocabulary vocabulary = new FoodEvidenceVocabulary();
        StructuredFoodEvidence evidence = evidence(
                vocabulary,
                Dimension.INGREDIENT, "해물",
                Dimension.TASTE, "칼칼한",
                Dimension.BROTH, "국물");
        InterpretedSearchCondition condition = condition(
                List.of(), List.of(), List.of(), List.of(), null,
                "칼칼한 해물 음식");
        IntegratedStoreSearchSlice first = repository.search(query(
                condition, List.of(), evidence, "relevance,desc", null, 1));
        IntegratedStoreSearchSlice second = repository.search(query(
                condition, List.of(), evidence, "relevance,desc",
                first.nextCursor(), 1));

        assertThat(first.content()).singleElement().satisfies(candidate -> {
            assertThat(candidate.storeId()).isEqualTo(threeDimensions.getId());
            assertThat(candidate.structuredRelevance()).isEqualTo(29);
        });
        assertThat(second.content()).singleElement().satisfies(candidate -> {
            assertThat(candidate.storeId()).isEqualTo(twoDimensionsHighLegacy.getId());
            assertThat(candidate.structuredRelevance()).isEqualTo(26);
            assertThat(candidate.relevanceTier()).isEqualTo(3);
        });
    }

    @Test
    @Transactional
    void explicitMenuSearchDoesNotAppendAttributeOnlyStores() {
        Store explicit = createStore(
                "명시 메뉴 매장", Region.SEOUL, "CHINESE", Set.of(), false);
        publishMenuWithSearchFields(
                explicit, "짬뽕", "해물 국물", 12_000,
                "BEVERAGE", List.of(), List.of("해물"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store attributeOnly = createStore(
                "속성 전용 매장", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                attributeOnly, "해물 전골", "칼칼한 해물 국물", 14_000,
                "BEVERAGE", List.of(), List.of("칼칼한", "해물", "국물"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        flushAndClear();

        FoodEvidenceVocabulary vocabulary = new FoodEvidenceVocabulary();
        StructuredFoodEvidence evidence = evidenceWithMenuFamily(
                vocabulary,
                StructuredFoodEvidenceSource.DETERMINISTIC,
                "짬뽕",
                Dimension.INGREDIENT, "해물",
                Dimension.TASTE, "칼칼한",
                Dimension.BROTH, "국물");
        IntegratedStoreSearchSlice result = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null, "짬뽕"),
                List.of("짬뽕"), evidence, "relevance,desc", null, 20));

        assertThat(result.content())
                .extracting(IntegratedStoreSearchCandidate::storeId)
                .containsExactly(explicit.getId());
    }

    @Test
    @Transactional
    void threeDimensionsOutrankLlmInferredMenuFamily() {
        Store inferredFamily = createStore(
                "LLM 계열 매장", Region.SEOUL, "CHINESE", Set.of(), false);
        publishMenuWithSearchFields(
                inferredFamily, "마라탕", "향신료 음식", 13_000,
                "BEVERAGE", List.of(), List.of("향신료"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store threeDimensions = createStore(
                "세 차원 매장", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                threeDimensions, "해물 전골", "칼칼한 해물 국물", 14_000,
                "BEVERAGE", List.of(), List.of("칼칼한", "해물", "국물"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        flushAndClear();

        FoodEvidenceVocabulary vocabulary = new FoodEvidenceVocabulary();
        StructuredFoodEvidence evidence = evidenceWithMenuFamily(
                vocabulary,
                StructuredFoodEvidenceSource.LLM,
                "마라탕",
                Dimension.INGREDIENT, "해물",
                Dimension.TASTE, "칼칼한",
                Dimension.BROTH, "국물");
        IntegratedStoreSearchSlice result = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null, "음식"),
                List.of(), evidence, "relevance,desc", null, 20));

        assertThat(result.content())
                .extracting(IntegratedStoreSearchCandidate::storeId)
                .containsExactly(threeDimensions.getId(), inferredFamily.getId());
        assertThat(result.content())
                .extracting(IntegratedStoreSearchCandidate::structuredRelevance)
                .containsExactly(9, 2);
    }

    @Test
    @Transactional
    void fourInformativeMenuFieldTokensProduceScoreAboveLegacyBound() {
        Store fourTokens = createStore(
                "원문 네 토큰 매장", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                fourTokens, "오늘의 전골", "칼칼한 해물 바질 토마토", 14_000,
                "BEVERAGE", List.of(), List.of("칼칼한", "해물", "바질", "토마토"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store oneToken = createStore(
                "원문 한 토큰 매장", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                oneToken, "오늘의 별미", "바질", 13_000,
                "BEVERAGE", List.of(), List.of("바질"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        flushAndClear();

        FoodEvidenceVocabulary vocabulary = new FoodEvidenceVocabulary();
        StructuredFoodEvidence evidence = evidence(
                vocabulary,
                Dimension.INGREDIENT, "해물",
                Dimension.TASTE, "칼칼한");
        IntegratedStoreSearchSlice result = repository.search(query(
                condition(List.of(), List.of(), List.of(), List.of(), null,
                        "칼칼한 해물 바질 토마토 음식 추천해줘"),
                List.of(), evidence, "relevance,desc", null, 20));

        assertThat(result.content()).singleElement().satisfies(candidate -> {
            assertThat(candidate.storeId()).isEqualTo(fourTokens.getId());
            assertThat(candidate.structuredRelevance()).isEqualTo(46);
        });
    }

    @Test
    @Transactional
    void structuredExpansionWithoutConceptsDoesNotAppendUnrelatedReverseMatches() {
        Store relevant = createStore(
                "구조화 확장 대상", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenuWithSearchFields(
                relevant, "해물 전골", "칼칼한 해물", 14_000,
                "BEVERAGE", List.of(), List.of("해물"),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        Store unrelated = createStore(
                "무관한 역방향 후보", Region.SEOUL, "KOREAN", Set.of(), false);
        publishMenu(
                unrelated, "초밥", 14_000, "BEVERAGE", List.of(),
                MenuSellingStatus.SELLING, MenuVisibility.VISIBLE, false);
        flushAndClear();

        StructuredFoodEvidence evidence = new DeterministicFoodEvidenceExtractor(
                new FoodEvidenceVocabulary()).extract("칼칼한 해물 음식");
        List<IntegratedStoreSearchCandidate> result = repository.searchExpanded(
                query(condition(), "relevance,desc", null, 20),
                List.of(),
                evidence,
                20);

        assertThat(result)
                .extracting(IntegratedStoreSearchCandidate::storeId)
                .containsExactly(relevant.getId());
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
        return publishMenuWithSearchFields(
                store,
                name,
                "",
                price,
                primaryCategory,
                secondaryCategories,
                List.of(),
                sellingStatus,
                visibility,
                retired);
    }

    private Menu publishMenuWithSearchFields(
            Store store,
            String name,
            String description,
            int price,
            String primaryCategory,
            List<String> secondaryCategories,
            List<String> localTags,
            MenuSellingStatus sellingStatus,
            MenuVisibility visibility,
            boolean retired
    ) {
        MenuContent content = menuContent(
                name, description, price, primaryCategory, secondaryCategories, localTags);
        Menu menu = Menu.create(store.getId(), content, store.getStoreOperatorAccountId(), NOW);
        menu.publish(NOW.plusSeconds(1));
        menu.changeSellingStatus(sellingStatus);
        menu.changeVisibility(visibility);
        if (retired) {
            menu.retire(NOW.plusSeconds(2));
        }
        return menuRepository.saveAndFlush(menu);
    }

    private MenuContent menuContent(
            String name,
            String description,
            int price,
            String primaryCategory,
            List<String> secondaryCategories,
            List<String> localTags
    ) {
        return new MenuContent(
                name,
                description,
                price,
                true,
                primaryCategory,
                secondaryCategories,
                localTags,
                true,
                true,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new OriginDisclosure("원재료", "대한민국")),
                false);
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

    private IntegratedStoreSearchQuery query(
            InterpretedSearchCondition condition,
            List<String> explicitMenuNames,
            String sort,
            String cursor,
            Integer size
    ) {
        return IntegratedStoreSearchQuery.from(
                condition, explicitMenuNames, sort, cursor, size, cursorCodec);
    }

    private IntegratedStoreSearchQuery query(
            InterpretedSearchCondition condition,
            List<String> explicitMenuNames,
            StructuredFoodEvidence evidence,
            String sort,
            String cursor,
            Integer size
    ) {
        return IntegratedStoreSearchQuery.from(
                condition,
                explicitMenuNames,
                evidence,
                false,
                false,
                cursorCodec.principalScope(null),
                sort,
                cursor,
                size,
                cursorCodec);
    }

    private static StructuredFoodEvidence evidence(
            FoodEvidenceVocabulary vocabulary,
            Object... dimensionsAndTerms
    ) {
        java.util.Map<Dimension, List<EvidenceTerm>> values =
                new java.util.EnumMap<>(Dimension.class);
        for (int index = 0; index < dimensionsAndTerms.length; index += 2) {
            Dimension dimension = (Dimension) dimensionsAndTerms[index];
            String term = (String) dimensionsAndTerms[index + 1];
            values.put(dimension, List.of(vocabulary.resolve(
                    dimension,
                    term,
                    StructuredFoodEvidenceSource.DETERMINISTIC).orElseThrow()));
        }
        return new StructuredFoodEvidence(
                List.of(),
                values.getOrDefault(Dimension.MENU_FAMILY, List.of()),
                values.getOrDefault(Dimension.INGREDIENT, List.of()),
                values.getOrDefault(Dimension.TASTE, List.of()),
                values.getOrDefault(Dimension.BROTH, List.of()),
                values.getOrDefault(Dimension.METHOD, List.of()),
                values.getOrDefault(Dimension.AROMA, List.of()),
                values.getOrDefault(Dimension.TEXTURE, List.of()),
                values.getOrDefault(Dimension.FORM, List.of()));
    }

    private static StructuredFoodEvidence evidenceWithMenuFamily(
            FoodEvidenceVocabulary vocabulary,
            StructuredFoodEvidenceSource source,
            String menuFamily,
            Object... dimensionsAndTerms
    ) {
        StructuredFoodEvidence dimensions = evidence(vocabulary, dimensionsAndTerms);
        EvidenceTerm family = vocabulary.resolve(
                        Dimension.MENU_FAMILY,
                        menuFamily,
                        source)
                .orElseThrow();
        return new StructuredFoodEvidence(
                List.of(), List.of(family),
                dimensions.ingredients(), dimensions.tastes(), dimensions.broths(),
                dimensions.methods(), dimensions.aromas(), dimensions.textures(),
                dimensions.forms());
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
