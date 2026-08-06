package com.miriyum.domain.store.search.repository;

import com.miriyum.domain.store.core.entity.QStore;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.enums.VerificationStatus;
import com.miriyum.domain.store.menu.entity.QMenu;
import com.miriyum.domain.store.menu.entity.QMenuVersion;
import com.miriyum.domain.store.menu.enums.MenuVersionStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.search.interpreter.PriceRange;
import com.miriyum.domain.store.search.query.IntegratedStoreSearchQuery;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.JPAExpressions;

/** 공개 상태와 승인 검색 조건을 타입 안전한 QueryDSL predicate로 조립한다. */
final class IntegratedStoreSearchPredicates {

    private static final char LIKE_ESCAPE = '!';

    private IntegratedStoreSearchPredicates() {
    }

    static BooleanBuilder create(QStore store, IntegratedStoreSearchQuery query) {
        BooleanBuilder predicate = new BooleanBuilder()
                .and(store.verificationStatus.eq(VerificationStatus.APPROVED))
                .and(store.operationStatus.ne(OperationStatus.CLOSED));
        addStoreConditions(predicate, store, query);
        addMenuAndKeywordConditions(predicate, store, query);
        return predicate;
    }

    private static void addStoreConditions(
            BooleanBuilder predicate,
            QStore store,
            IntegratedStoreSearchQuery query
    ) {
        if (!query.regionCodes().isEmpty()) {
            predicate.and(store.region.stringValue().in(query.regionCodes()));
        }
        if (!query.storeCategoryCodes().isEmpty()) {
            predicate.and(store.storeCategoryCode.in(query.storeCategoryCodes()));
        }
        if (!query.tagCodes().isEmpty()) {
            predicate.and(store.tagCodes.any().in(query.tagCodes()));
        }
    }

    private static void addMenuAndKeywordConditions(
            BooleanBuilder predicate,
            QStore store,
            IntegratedStoreSearchQuery query
    ) {
        boolean hasMenuFilter = !query.menuCategoryCodes().isEmpty()
                || query.priceRange() != null;
        if (hasMenuFilter) {
            predicate.and(currentPublishedVisibleMenuExists(store, query, false));
        }
        if (!query.remainingKeyword().isEmpty()) {
            String pattern = literalContainsPattern(query.remainingKeyword());
            BooleanExpression storeNameMatches = store.name.likeIgnoreCase(
                    pattern, LIKE_ESCAPE);
            BooleanExpression regionNameMatches = localizedRegionName(store).likeIgnoreCase(
                    pattern, LIKE_ESCAPE);
            predicate.and(storeNameMatches
                    .or(regionNameMatches)
                    .or(currentPublishedVisibleMenuExists(store, query, true)));
        }
    }

    private static BooleanExpression currentPublishedVisibleMenuExists(
            QStore store,
            IntegratedStoreSearchQuery query,
            boolean requireKeyword
    ) {
        QMenu menu = new QMenu(requireKeyword ? "keywordMenu" : "filteredMenu");
        QMenuVersion version = new QMenuVersion(
                requireKeyword ? "keywordMenuVersion" : "filteredMenuVersion");
        BooleanBuilder menuPredicate = new BooleanBuilder()
                .and(menu.storeId.eq(store.id))
                .and(menu.retired.isFalse())
                .and(menu.visibility.eq(MenuVisibility.VISIBLE))
                .and(menu.publishedVersionNumber.eq(version.versionNumber))
                .and(version.status.eq(MenuVersionStatus.PUBLISHED));

        if (!query.menuCategoryCodes().isEmpty()) {
            menuPredicate.and(version.primaryCategoryCode.in(query.menuCategoryCodes())
                    .or(version.secondaryCategoryCodes.any().in(query.menuCategoryCodes())));
        }
        addPricePredicate(menuPredicate, version, query.priceRange());
        if (requireKeyword) {
            menuPredicate.and(version.name.likeIgnoreCase(
                    literalContainsPattern(query.remainingKeyword()), LIKE_ESCAPE));
        }

        return JPAExpressions.selectOne()
                .from(menu)
                .join(menu.versions, version)
                .where(menuPredicate)
                .exists();
    }

    private static void addPricePredicate(
            BooleanBuilder predicate,
            QMenuVersion version,
            PriceRange priceRange
    ) {
        if (priceRange == null) {
            return;
        }
        if (priceRange.minInclusive() != null) {
            long minimum = priceRange.minInclusive();
            predicate.and(minimum > Integer.MAX_VALUE
                    ? version.price.gt(Integer.MAX_VALUE)
                    : version.price.goe((int) minimum));
        }
        if (priceRange.maxInclusive() != null) {
            long maximum = priceRange.maxInclusive();
            if (maximum <= Integer.MAX_VALUE) {
                predicate.and(version.price.loe((int) maximum));
            }
        }
    }

    private static String literalContainsPattern(String value) {
        return "%" + value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    private static StringExpression localizedRegionName(QStore store) {
        return new CaseBuilder()
                .when(store.region.eq(Region.SEOUL)).then("서울")
                .when(store.region.eq(Region.BUSAN)).then("부산")
                .when(store.region.eq(Region.DAEGU)).then("대구")
                .when(store.region.eq(Region.DAEJEON)).then("대전")
                .when(store.region.eq(Region.GWANGJU)).then("광주")
                .otherwise("");
    }
}
