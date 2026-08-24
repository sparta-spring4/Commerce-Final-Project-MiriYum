package com.miriyum.domain.search.repository;

import com.miriyum.domain.menu.entity.QMenu;
import com.miriyum.domain.menu.entity.QMenuVersion;
import com.miriyum.domain.menu.enums.MenuVersionStatus;
import com.miriyum.domain.store.entity.QStore;
import com.miriyum.domain.store.enums.GeocodingStatus;
import com.miriyum.domain.search.query.IntegratedSearchCursor;
import com.miriyum.domain.search.query.IntegratedSearchCursorCodec;
import com.miriyum.domain.search.query.IntegratedStoreSearchQuery;
import com.miriyum.domain.search.query.IntegratedStoreSearchSort;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** 승인 조건만 QueryDSL predicate와 projection으로 조립하는 2차 통합 검색 조회다. */
@Repository
public class IntegratedStoreSearchRepository {

    private static final int MAX_EXPANDED_CANDIDATES = 200;
    private static final int MAX_EXPLICIT_MENU_NAMES = 100;
    private static final int FORWARD_EXPANDED_TIER = 1;
    private static final int REVERSE_EXPANDED_TIER = 0;

    private final JPAQueryFactory queryFactory;
    private final IntegratedSearchCursorCodec cursorCodec;

    public IntegratedStoreSearchRepository(
            EntityManager entityManager,
            IntegratedSearchCursorCodec cursorCodec
    ) {
        this.queryFactory = new JPAQueryFactory(entityManager);
        this.cursorCodec = cursorCodec;
    }

    public IntegratedStoreSearchSlice search(IntegratedStoreSearchQuery query) {
        QStore store = QStore.store;
        BooleanBuilder predicate = IntegratedStoreSearchPredicates.create(store, query);
        query.cursor().ifPresent(cursor -> predicate.and(cursorPredicate(store, query, cursor)));

        NumberExpression<Integer> relevance = relevance(store, query);
        BooleanExpression currentVerifiedCoordinates = store.geocodingStatus
                .eq(GeocodingStatus.VERIFIED)
                .and(store.geocodingAddressVersion.eq(store.addressVersion));
        List<IntegratedStoreSearchCandidate> fetched = queryFactory
                .select(Projections.constructor(
                        IntegratedStoreSearchCandidate.class,
                        store.id,
                        store.name,
                        store.region,
                        store.address,
                        store.storeCategoryCode,
                        store.operationStatus,
                        store.reservationEnabled,
                        store.menuHoldEnabled,
                        store.pickupEnabled,
                        store.createdAt,
                        relevance,
                        new CaseBuilder().when(currentVerifiedCoordinates)
                                .then(store.latitude)
                                .otherwise(Expressions.nullExpression(BigDecimal.class)),
                        new CaseBuilder().when(currentVerifiedCoordinates)
                                .then(store.longitude)
                                .otherwise(Expressions.nullExpression(BigDecimal.class))))
                .from(store)
                .where(predicate)
                .orderBy(orderBy(store, relevance, query.sort()))
                .limit((long) query.size() + 1)
                .fetch();

        boolean hasNext = fetched.size() > query.size();
        List<IntegratedStoreSearchCandidate> content = hasNext
                ? List.copyOf(fetched.subList(0, query.size()))
                : List.copyOf(fetched);
        String nextCursor = hasNext
                ? encodeCursor(query, content.getLast())
                : null;
        return new IntegratedStoreSearchSlice(content, nextCursor);
    }

    /** 문장에 직접 포함된 current published 메뉴명을 한 번 조회해 겹치는 짧은 이름을 제거한다. */
    public List<String> resolveMostSpecificPublishedMenuNames(String remainingKeyword) {
        if (remainingKeyword == null || remainingKeyword.isBlank()) {
            return List.of();
        }
        QMenu menu = new QMenu("explicitNameMenu");
        QMenuVersion version = new QMenuVersion("explicitNameMenuVersion");
        QMenuVersion longerVersion = new QMenuVersion("longerExplicitNameMenuVersion");
        List<String> candidateNames = candidateMenuNames(remainingKeyword);
        if (candidateNames.isEmpty()) {
            return List.of();
        }
        NumberExpression<Integer> containedInLongerName = Expressions.numberTemplate(
                Integer.class,
                "locate(lower({0}), lower({1}))",
                version.name,
                longerVersion.name);
        return queryFactory
                .select(version.name)
                .distinct()
                .from(menu)
                .join(menu.versions, version)
                .leftJoin(longerVersion)
                .on(
                        longerVersion.menu.retired.isFalse(),
                        longerVersion.menu.publishedVersionNumber
                                .eq(longerVersion.versionNumber),
                        longerVersion.status.eq(MenuVersionStatus.PUBLISHED),
                        longerVersion.name.in(candidateNames),
                        longerVersion.name.length().gt(version.name.length()),
                        containedInLongerName.gt(0))
                .where(
                        menu.retired.isFalse(),
                        menu.publishedVersionNumber.eq(version.versionNumber),
                        version.status.eq(MenuVersionStatus.PUBLISHED),
                        IntegratedStoreSearchPredicates.reverseMenuNameGuard(version),
                        version.name.in(candidateNames),
                        longerVersion.id.isNull())
                .orderBy(version.name.length().desc(), version.name.asc())
                .limit(MAX_EXPLICIT_MENU_NAMES)
                .fetch();
    }

    static List<String> candidateMenuNames(String remainingKeyword) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        int[] codePoints = remainingKeyword.codePoints().toArray();
        for (int start = 0; start < codePoints.length; start++) {
            for (int end = start + 2; end <= codePoints.length; end++) {
                String candidate = new String(codePoints, start, end - start);
                if (candidate.equals(candidate.trim())
                        && IntegratedStoreSearchPredicates
                        .isEligibleReverseMenuName(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates.stream().sorted().toList();
    }

    /** LLM 개념을 현재 공개 메뉴에 대조하고 원래 구조화 조건을 유지한다. */
    public List<IntegratedStoreSearchCandidate> searchExpanded(
            IntegratedStoreSearchQuery query,
            List<String> concepts,
            int limit
    ) {
        if (concepts == null || concepts.isEmpty() || limit < 1) {
            return List.of();
        }
        List<String> validated = concepts.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (validated.isEmpty()) {
            return List.of();
        }
        int boundedLimit = Math.min(limit, MAX_EXPANDED_CANDIDATES);
        QStore store = QStore.store;
        List<IntegratedStoreSearchCandidate> forward = searchExpandedTier(
                store,
                IntegratedStoreSearchPredicates.createExpandedForward(
                        store, query, validated),
                FORWARD_EXPANDED_TIER,
                boundedLimit);
        if (forward.size() >= boundedLimit) {
            return forward;
        }
        BooleanBuilder reversePredicate = IntegratedStoreSearchPredicates.createExpandedReverse(
                store, query, validated);
        if (!forward.isEmpty()) {
            reversePredicate.and(store.id.notIn(forward.stream()
                    .map(IntegratedStoreSearchCandidate::storeId)
                    .toList()));
        }
        List<IntegratedStoreSearchCandidate> reverse = searchExpandedTier(
                store,
                reversePredicate,
                REVERSE_EXPANDED_TIER,
                boundedLimit - forward.size());
        List<IntegratedStoreSearchCandidate> combined = new ArrayList<>(boundedLimit);
        combined.addAll(forward);
        combined.addAll(reverse);
        return List.copyOf(combined);
    }

    private List<IntegratedStoreSearchCandidate> searchExpandedTier(
            QStore store,
            BooleanBuilder predicate,
            int relevanceTier,
            int limit
    ) {
        BooleanExpression currentVerifiedCoordinates = store.geocodingStatus
                .eq(GeocodingStatus.VERIFIED)
                .and(store.geocodingAddressVersion.eq(store.addressVersion));
        return List.copyOf(queryFactory
                .select(Projections.constructor(
                        IntegratedStoreSearchCandidate.class,
                        store.id,
                        store.name,
                        store.region,
                        store.address,
                        store.storeCategoryCode,
                        store.operationStatus,
                        store.reservationEnabled,
                        store.menuHoldEnabled,
                        store.pickupEnabled,
                        store.createdAt,
                        Expressions.asNumber(relevanceTier),
                        new CaseBuilder().when(currentVerifiedCoordinates)
                                .then(store.latitude)
                                .otherwise(Expressions.nullExpression(BigDecimal.class)),
                        new CaseBuilder().when(currentVerifiedCoordinates)
                                .then(store.longitude)
                                .otherwise(Expressions.nullExpression(BigDecimal.class))))
                .from(store)
                .where(predicate)
                .orderBy(store.name.asc(), store.id.asc())
                .limit(limit)
                .fetch());
    }

    /** 후보 순서를 유지하며 응답 직전 공개·운영·모드·검증 좌표를 다시 읽는다. */
    public List<IntegratedStoreSearchCandidate> refreshCurrentlyPublic(
            List<IntegratedStoreSearchCandidate> candidates
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        QStore store = QStore.store;
        BooleanExpression currentVerifiedCoordinates = store.geocodingStatus
                .eq(GeocodingStatus.VERIFIED)
                .and(store.geocodingAddressVersion.eq(store.addressVersion));
        List<CurrentIntegratedState> states = queryFactory
                .select(Projections.constructor(
                        CurrentIntegratedState.class,
                        store.id,
                        store.operationStatus,
                        store.reservationEnabled,
                        store.menuHoldEnabled,
                        store.pickupEnabled,
                        new CaseBuilder().when(currentVerifiedCoordinates)
                                .then(store.latitude)
                                .otherwise(Expressions.nullExpression(BigDecimal.class)),
                        new CaseBuilder().when(currentVerifiedCoordinates)
                                .then(store.longitude)
                                .otherwise(Expressions.nullExpression(BigDecimal.class))))
                .from(store)
                .where(store.id.in(candidates.stream()
                                .map(IntegratedStoreSearchCandidate::storeId)
                                .toList())
                        .and(store.verificationStatus.eq(
                                com.miriyum.domain.store.enums.VerificationStatus.APPROVED))
                        .and(store.operationStatus.ne(
                                com.miriyum.domain.store.enums.OperationStatus.CLOSED)))
                .fetch();
        Map<Long, CurrentIntegratedState> byId = new LinkedHashMap<>();
        states.forEach(state -> byId.put(state.storeId(), state));
        return candidates.stream()
                .map(candidate -> refresh(candidate, byId.get(candidate.storeId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 현재 query 정렬에서 처리한 마지막 후보 뒤를 가리키는 cursor를 만든다. */
    public String cursorAfter(
            IntegratedStoreSearchQuery query,
            IntegratedStoreSearchCandidate candidate
    ) {
        return encodeCursor(query, candidate);
    }

    private static BooleanExpression cursorPredicate(
            QStore store,
            IntegratedStoreSearchQuery query,
            IntegratedSearchCursor cursor
    ) {
        return switch (query.sort()) {
            case RELEVANCE_DESC -> relevanceCursorPredicate(
                    store, relevance(store, query), cursor);
            case RECOMMENDATION_DESC -> relevanceCursorPredicate(
                    store, relevance(store, query), cursor);
            case NAME_ASC -> store.name.gt(cursor.sortValue())
                    .or(store.name.eq(cursor.sortValue()).and(store.id.gt(cursor.storeId())));
            case NAME_DESC -> store.name.lt(cursor.sortValue())
                    .or(store.name.eq(cursor.sortValue()).and(store.id.gt(cursor.storeId())));
            case CREATED_AT_ASC -> dateCursorPredicate(store, cursor, true);
            case CREATED_AT_DESC -> dateCursorPredicate(store, cursor, false);
        };
    }

    private static BooleanExpression relevanceCursorPredicate(
            QStore store,
            NumberExpression<Integer> relevance,
            IntegratedSearchCursor cursor
    ) {
        return relevance.lt(cursor.relevanceTier())
                .or(relevance.eq(cursor.relevanceTier()).and(
                        store.name.gt(cursor.sortValue())
                                .or(store.name.eq(cursor.sortValue())
                                        .and(store.id.gt(cursor.storeId())))));
    }

    private static BooleanExpression dateCursorPredicate(
            QStore store,
            IntegratedSearchCursor cursor,
            boolean ascending
    ) {
        try {
            LocalDateTime value = LocalDateTime.parse(cursor.sortValue());
            BooleanExpression afterValue = ascending
                    ? store.createdAt.gt(value)
                    : store.createdAt.lt(value);
            return afterValue.or(
                    store.createdAt.eq(value).and(store.id.gt(cursor.storeId())));
        } catch (DateTimeParseException exception) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    @SuppressWarnings("unchecked")
    private static OrderSpecifier<?>[] orderBy(
            QStore store,
            NumberExpression<Integer> relevance,
            IntegratedStoreSearchSort sort
    ) {
        List<OrderSpecifier<?>> order = new ArrayList<>(2);
        switch (sort) {
            case RELEVANCE_DESC, RECOMMENDATION_DESC -> {
                order.add(relevance.desc());
                order.add(store.name.asc());
            }
            case NAME_ASC -> order.add(store.name.asc());
            case NAME_DESC -> order.add(store.name.desc());
            case CREATED_AT_ASC -> order.add(store.createdAt.asc());
            case CREATED_AT_DESC -> order.add(store.createdAt.desc());
        }
        order.add(store.id.asc());
        return order.toArray(OrderSpecifier[]::new);
    }

    private String encodeCursor(
            IntegratedStoreSearchQuery query,
            IntegratedStoreSearchCandidate candidate
    ) {
        String sortValue = switch (query.sort()) {
            case RELEVANCE_DESC, RECOMMENDATION_DESC, NAME_ASC, NAME_DESC ->
                    candidate.name();
            case CREATED_AT_ASC, CREATED_AT_DESC -> candidate.createdAt().toString();
        };
        return cursorCodec.encode(
                query, candidate.relevanceTier(), sortValue, candidate.storeId());
    }

    private static NumberExpression<Integer> relevance(
            QStore store,
            IntegratedStoreSearchQuery query
    ) {
        if (query.remainingKeyword().isEmpty()) {
            return new CaseBuilder()
                    .when(store.id.isNotNull()).then(0)
                    .otherwise(-1);
        }
        String keyword = query.remainingKeyword();
        String pattern = IntegratedStoreSearchPredicates.literalContainsPattern(keyword);
        BooleanExpression menuMatches =
                IntegratedStoreSearchPredicates.currentPublishedVisibleMenuExists(
                        store, query, true);
        BooleanExpression regionOrAddressMatches =
                IntegratedStoreSearchPredicates.localizedRegionName(store)
                        .likeIgnoreCase(pattern, '!')
                        .or(store.address.likeIgnoreCase(pattern, '!'));
        return new CaseBuilder()
                .when(store.name.equalsIgnoreCase(keyword)).then(4)
                .when(store.name.likeIgnoreCase(pattern, '!')).then(3)
                .when(menuMatches).then(2)
                .when(regionOrAddressMatches).then(1)
                .otherwise(0);
    }

    private static IntegratedStoreSearchCandidate refresh(
            IntegratedStoreSearchCandidate candidate,
            CurrentIntegratedState state
    ) {
        if (state == null) {
            return null;
        }
        return new IntegratedStoreSearchCandidate(
                candidate.storeId(),
                candidate.name(),
                candidate.region(),
                candidate.address(),
                candidate.storeCategoryCode(),
                state.operationStatus(),
                state.reservationEnabled(),
                state.menuHoldEnabled(),
                state.pickupEnabled(),
                candidate.createdAt(),
                candidate.relevanceTier(),
                state.latitude(),
                state.longitude());
    }

    public record CurrentIntegratedState(
            long storeId,
            com.miriyum.domain.store.enums.OperationStatus operationStatus,
            boolean reservationEnabled,
            boolean menuHoldEnabled,
            boolean pickupEnabled,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }

}
