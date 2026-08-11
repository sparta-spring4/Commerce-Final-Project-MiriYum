package com.miriyum.domain.recommendation.ranking;

import com.miriyum.domain.store.entity.QStore;
import com.miriyum.domain.menu.entity.QMenu;
import com.miriyum.domain.menu.entity.QMenuVersion;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVersionStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
public class RecommendationSignalRepository {

    private final JPAQueryFactory queryFactory;

    public RecommendationSignalRepository(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    public Map<Long, RecommendationCandidateSignals> findSignals(
            List<Long> storeIds,
            RecommendationSearchSignals condition
    ) {
        Objects.requireNonNull(storeIds, "storeIds are required");
        Objects.requireNonNull(condition, "condition is required");
        if (storeIds.stream().anyMatch(id -> id == null || id <= 0)
                || storeIds.stream().distinct().count() != storeIds.size()) {
            throw new IllegalArgumentException("storeIds must be unique and positive");
        }
        if (storeIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, MutableSignals> mutable = new LinkedHashMap<>();
        storeIds.forEach(storeId -> mutable.put(storeId, new MutableSignals()));
        loadStoreCategories(storeIds, Set.copyOf(condition.storeCategoryCodes()), mutable);
        loadTags(storeIds, Set.copyOf(condition.tagCodes()), mutable);
        loadCurrentMenus(storeIds, Set.copyOf(condition.menuCategoryCodes()), mutable);
        loadSecondaryCategories(
                storeIds, Set.copyOf(condition.menuCategoryCodes()), mutable);

        Map<Long, RecommendationCandidateSignals> result = new LinkedHashMap<>();
        mutable.forEach((storeId, signals) -> result.put(storeId, signals.toImmutable()));
        return Collections.unmodifiableMap(result);
    }

    private void loadStoreCategories(
            List<Long> storeIds,
            Set<String> requestedCategories,
            Map<Long, MutableSignals> signals
    ) {
        QStore store = QStore.store;
        List<Tuple> rows = queryFactory
                .select(store.id, store.storeCategoryCode)
                .from(store)
                .where(store.id.in(storeIds))
                .fetch();
        for (Tuple row : rows) {
            Long storeId = row.get(store.id);
            String category = row.get(store.storeCategoryCode);
            MutableSignals target = signals.get(storeId);
            if (target != null && requestedCategories.contains(category)) {
                target.storeCategoryMatch = true;
            }
        }
    }

    private void loadTags(
            List<Long> storeIds,
            Set<String> requestedTags,
            Map<Long, MutableSignals> signals
    ) {
        if (requestedTags.isEmpty()) {
            return;
        }
        QStore store = QStore.store;
        StringPath tag = Expressions.stringPath("recommendationTag");
        List<Tuple> rows = queryFactory
                .select(store.id, tag)
                .from(store)
                .join(store.tagCodes, tag)
                .where(store.id.in(storeIds).and(tag.in(requestedTags)))
                .fetch();
        for (Tuple row : rows) {
            Long storeId = row.get(store.id);
            String value = row.get(tag);
            MutableSignals target = signals.get(storeId);
            if (target != null && value != null) {
                target.matchedTags.add(value);
            }
        }
    }

    private void loadCurrentMenus(
            List<Long> storeIds,
            Set<String> requestedMenuCategories,
            Map<Long, MutableSignals> signals
    ) {
        QMenu menu = QMenu.menu;
        QMenuVersion version = new QMenuVersion("recommendationMenuVersion");
        List<Tuple> rows = queryFactory
                .select(menu.storeId, menu.id, version.primaryCategoryCode)
                .from(menu)
                .join(menu.versions, version)
                .where(currentMenuPredicate(menu, version, storeIds))
                .fetch();
        for (Tuple row : rows) {
            Long storeId = row.get(menu.storeId);
            Long menuId = row.get(menu.id);
            String primaryCategory = row.get(version.primaryCategoryCode);
            MutableSignals target = signals.get(storeId);
            if (target == null || menuId == null) {
                continue;
            }
            target.currentMenuIds.add(menuId);
            if (requestedMenuCategories.contains(primaryCategory)) {
                target.menuPrimaryCategoryMatch = true;
            }
        }
    }

    private void loadSecondaryCategories(
            List<Long> storeIds,
            Set<String> requestedMenuCategories,
            Map<Long, MutableSignals> signals
    ) {
        if (requestedMenuCategories.isEmpty()) {
            return;
        }
        QMenu menu = new QMenu("recommendationSecondaryMenu");
        QMenuVersion version = new QMenuVersion("recommendationSecondaryVersion");
        StringPath secondary = Expressions.stringPath("recommendationSecondaryCategory");
        List<Tuple> rows = queryFactory
                .select(menu.storeId, secondary)
                .from(menu)
                .join(menu.versions, version)
                .join(version.secondaryCategoryCodes, secondary)
                .where(currentMenuPredicate(menu, version, storeIds)
                        .and(secondary.in(requestedMenuCategories)))
                .fetch();
        for (Tuple row : rows) {
            Long storeId = row.get(menu.storeId);
            String category = row.get(secondary);
            MutableSignals target = signals.get(storeId);
            if (target != null && category != null) {
                target.matchedSecondaryCategories.add(category);
            }
        }
    }

    private static com.querydsl.core.types.dsl.BooleanExpression currentMenuPredicate(
            QMenu menu,
            QMenuVersion version,
            List<Long> storeIds
    ) {
        return menu.storeId.in(storeIds)
                .and(menu.retired.isFalse())
                .and(menu.visibility.eq(MenuVisibility.VISIBLE))
                .and(menu.sellingStatus.eq(MenuSellingStatus.SELLING))
                .and(menu.publishedVersionNumber.eq(version.versionNumber))
                .and(version.status.eq(MenuVersionStatus.PUBLISHED));
    }

    private static final class MutableSignals {
        private boolean storeCategoryMatch;
        private boolean menuPrimaryCategoryMatch;
        private final Set<String> matchedSecondaryCategories = new LinkedHashSet<>();
        private final Set<String> matchedTags = new LinkedHashSet<>();
        private final Set<Long> currentMenuIds = new LinkedHashSet<>();

        private RecommendationCandidateSignals toImmutable() {
            return new RecommendationCandidateSignals(
                    storeCategoryMatch,
                    menuPrimaryCategoryMatch,
                    matchedSecondaryCategories.size(),
                    matchedTags.size(),
                    currentMenuIds);
        }
    }
}
