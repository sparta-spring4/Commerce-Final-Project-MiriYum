package com.miriyum.domain.store.search.repository;

import com.miriyum.domain.store.core.entity.QStore;
import com.miriyum.domain.store.search.query.IntegratedSearchCursor;
import com.miriyum.domain.store.search.query.IntegratedSearchCursorCodec;
import com.miriyum.domain.store.search.query.IntegratedStoreSearchQuery;
import com.miriyum.domain.store.search.query.IntegratedStoreSearchSort;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 승인 조건만 QueryDSL predicate와 projection으로 조립하는 2차 통합 검색 조회다. */
@Repository
public class IntegratedStoreSearchRepository {

    private final JPAQueryFactory queryFactory;

    public IntegratedStoreSearchRepository(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    public IntegratedStoreSearchSlice search(IntegratedStoreSearchQuery query) {
        QStore store = QStore.store;
        BooleanBuilder predicate = IntegratedStoreSearchPredicates.create(store, query);
        query.cursor().ifPresent(cursor -> predicate.and(cursorPredicate(store, query, cursor)));

        List<StoreSearchCandidate> fetched = queryFactory
                .select(Projections.constructor(
                        StoreSearchCandidate.class,
                        store.id,
                        store.name,
                        store.region,
                        store.address,
                        store.storeCategoryCode,
                        store.operationStatus,
                        store.reservationEnabled,
                        store.menuHoldEnabled,
                        store.pickupEnabled,
                        store.createdAt))
                .from(store)
                .where(predicate)
                .orderBy(orderBy(store, query.sort()))
                .limit((long) query.size() + 1)
                .fetch();

        boolean hasNext = fetched.size() > query.size();
        List<StoreSearchCandidate> content = hasNext
                ? List.copyOf(fetched.subList(0, query.size()))
                : List.copyOf(fetched);
        String nextCursor = hasNext
                ? encodeCursor(query, content.getLast())
                : null;
        return new IntegratedStoreSearchSlice(content, nextCursor);
    }

    private static BooleanExpression cursorPredicate(
            QStore store,
            IntegratedStoreSearchQuery query,
            IntegratedSearchCursor cursor
    ) {
        return switch (query.sort()) {
            case NAME_ASC -> store.name.gt(cursor.sortValue())
                    .or(store.name.eq(cursor.sortValue()).and(store.id.gt(cursor.storeId())));
            case NAME_DESC -> store.name.lt(cursor.sortValue())
                    .or(store.name.eq(cursor.sortValue()).and(store.id.gt(cursor.storeId())));
            case CREATED_AT_ASC -> dateCursorPredicate(store, cursor, true);
            case CREATED_AT_DESC -> dateCursorPredicate(store, cursor, false);
        };
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
            IntegratedStoreSearchSort sort
    ) {
        List<OrderSpecifier<?>> order = new ArrayList<>(2);
        switch (sort) {
            case NAME_ASC -> order.add(store.name.asc());
            case NAME_DESC -> order.add(store.name.desc());
            case CREATED_AT_ASC -> order.add(store.createdAt.asc());
            case CREATED_AT_DESC -> order.add(store.createdAt.desc());
        }
        order.add(store.id.asc());
        return order.toArray(OrderSpecifier[]::new);
    }

    private static String encodeCursor(
            IntegratedStoreSearchQuery query,
            StoreSearchCandidate candidate
    ) {
        String sortValue = switch (query.sort()) {
            case NAME_ASC, NAME_DESC -> candidate.name();
            case CREATED_AT_ASC, CREATED_AT_DESC -> candidate.createdAt().toString();
        };
        return IntegratedSearchCursorCodec.encode(query, sortValue, candidate.storeId());
    }

}
