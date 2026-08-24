package com.miriyum.domain.search.repository;

import com.miriyum.domain.store.entity.QStore;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.miriyum.domain.menu.entity.QMenu;
import com.miriyum.domain.menu.entity.QMenuVersion;
import com.miriyum.domain.menu.enums.MenuVersionStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.search.interpreter.PriceRange;
import com.miriyum.domain.search.query.IntegratedStoreSearchQuery;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.JPAExpressions;
import java.util.List;
import java.util.Set;

/** 공개 상태와 승인 검색 조건을 타입 안전한 QueryDSL predicate로 조립한다. */
final class IntegratedStoreSearchPredicates {

    private static final char LIKE_ESCAPE = '!';
    private static final Set<String> REVERSE_MATCH_EXCLUDED_MENU_NAMES = Set.of(
            "면", "탕", "국", "밥", "메뉴", "음식", "요리", "식사", "세트", "정식", "음료");

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

    static BooleanBuilder createExpandedForward(
            QStore store,
            IntegratedStoreSearchQuery query,
            List<String> concepts
    ) {
        BooleanBuilder predicate = new BooleanBuilder()
                .and(store.verificationStatus.eq(VerificationStatus.APPROVED))
                .and(store.operationStatus.ne(OperationStatus.CLOSED));
        addStoreConditions(predicate, store, query);
        predicate.and(currentExpandedForwardMenuExists(store, query, concepts));
        return predicate;
    }

    static BooleanBuilder createExpandedReverse(
            QStore store,
            IntegratedStoreSearchQuery query,
            List<String> concepts
    ) {
        BooleanBuilder predicate = new BooleanBuilder()
                .and(store.verificationStatus.eq(VerificationStatus.APPROVED))
                .and(store.operationStatus.ne(OperationStatus.CLOSED));
        addStoreConditions(predicate, store, query);
        predicate.and(currentExpandedReverseMenuExists(store, query, concepts));
        return predicate;
    }

    private static BooleanExpression currentExpandedForwardMenuExists(
            QStore store,
            IntegratedStoreSearchQuery query,
            List<String> concepts
    ) {
        QMenu menu = new QMenu("expandedForwardMenu");
        QMenuVersion version = new QMenuVersion("expandedForwardMenuVersion");
        BooleanBuilder conceptMatches = new BooleanBuilder();
        for (String concept : concepts) {
            String pattern = literalContainsPattern(concept);
            conceptMatches.or(version.name.likeIgnoreCase(pattern, LIKE_ESCAPE)
                    .or(version.description.likeIgnoreCase(pattern, LIKE_ESCAPE))
                    .or(version.primaryCategoryCode.likeIgnoreCase(pattern, LIKE_ESCAPE))
                    .or(version.secondaryCategoryCodes.any()
                            .likeIgnoreCase(pattern, LIKE_ESCAPE))
                    .or(version.localTags.any().likeIgnoreCase(pattern, LIKE_ESCAPE)));
        }
        return currentExpandedMenuExists(store, menu, version, query, conceptMatches);
    }

    private static BooleanExpression currentExpandedReverseMenuExists(
            QStore store,
            IntegratedStoreSearchQuery query,
            List<String> concepts
    ) {
        QMenu menu = new QMenu("expandedReverseMenu");
        QMenuVersion version = new QMenuVersion("expandedReverseMenuVersion");
        BooleanBuilder conceptMatches = new BooleanBuilder();
        for (String concept : concepts) {
            conceptMatches.or(menuNameContainedIn(version, concept));
        }
        conceptMatches.and(reverseMenuNameGuard(version));
        return currentExpandedMenuExists(store, menu, version, query, conceptMatches);
    }

    private static BooleanExpression currentExpandedMenuExists(
            QStore store,
            QMenu menu,
            QMenuVersion version,
            IntegratedStoreSearchQuery query,
            BooleanBuilder conceptMatches
    ) {
        BooleanBuilder expandedMenu = new BooleanBuilder()
                .and(menu.storeId.eq(store.id))
                .and(menu.retired.isFalse())
                .and(menu.visibility.eq(MenuVisibility.VISIBLE))
                .and(menu.publishedVersionNumber.eq(version.versionNumber))
                .and(version.status.eq(MenuVersionStatus.PUBLISHED))
                .and(conceptMatches);
        if (!query.menuCategoryCodes().isEmpty()) {
            expandedMenu.and(version.primaryCategoryCode.in(query.menuCategoryCodes())
                    .or(version.secondaryCategoryCodes.any()
                            .in(query.menuCategoryCodes())));
        }
        addPricePredicate(expandedMenu, version, query.priceRange());
        return JPAExpressions.selectOne()
                .from(menu)
                .join(menu.versions, version)
                .where(expandedMenu)
                .exists();
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
            BooleanExpression addressMatches = store.address.likeIgnoreCase(
                    pattern, LIKE_ESCAPE);
            predicate.and(storeNameMatches
                    .or(regionNameMatches)
                    .or(addressMatches)
                    .or(currentPublishedVisibleMenuExists(store, query, true)));
        }
    }

    static BooleanExpression currentPublishedVisibleMenuExists(
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
            BooleanExpression wholeKeywordInMenu = version.name.likeIgnoreCase(
                    literalContainsPattern(query.remainingKeyword()), LIKE_ESCAPE);
            BooleanExpression explicitMenuName = query.explicitMenuNames().isEmpty()
                    ? Expressions.FALSE
                    : version.name.trim().in(query.explicitMenuNames());
            menuPredicate.and(wholeKeywordInMenu.or(explicitMenuName));
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

    static String literalContainsPattern(String value) {
        return "%" + value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    private static BooleanExpression menuNameContainedIn(
            QMenuVersion version,
            String text
    ) {
        return Expressions.booleanTemplate(
                "locate(lower({0}), lower({1})) > 0", version.name, text);
    }

    static BooleanExpression reverseMenuNameGuard(QMenuVersion version) {
        return version.name.trim().length().goe(2)
                .and(version.name.trim().notIn(REVERSE_MATCH_EXCLUDED_MENU_NAMES));
    }

    static boolean isEligibleReverseMenuName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        return trimmed.codePointCount(0, trimmed.length()) >= 2
                && !REVERSE_MATCH_EXCLUDED_MENU_NAMES.contains(trimmed);
    }

    static StringExpression localizedRegionName(QStore store) {
        return new CaseBuilder()
                .when(store.region.eq(Region.SEOUL)).then("서울")
                .when(store.region.eq(Region.BUSAN)).then("부산")
                .when(store.region.eq(Region.DAEGU)).then("대구")
                .when(store.region.eq(Region.DAEJEON)).then("대전")
                .when(store.region.eq(Region.GWANGJU)).then("광주")
                .otherwise("");
    }
}
