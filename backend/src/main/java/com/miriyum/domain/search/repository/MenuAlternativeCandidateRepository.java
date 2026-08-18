package com.miriyum.domain.search.repository;

import com.miriyum.domain.menu.entity.QMenu;
import com.miriyum.domain.menu.entity.QMenuVersion;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVersionStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.menu.model.QAllergenDisclosure;
import com.miriyum.domain.search.dto.contract.MenuAlternativeAllergenView;
import com.miriyum.domain.search.dto.contract.MenuAlternativeCandidateView;
import com.miriyum.domain.search.dto.contract.MenuAlternativeSourceView;
import com.miriyum.domain.search.geo.BoundingBox;
import com.miriyum.domain.store.entity.QStore;
import com.miriyum.domain.store.enums.GeocodingStatus;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MenuAlternativeCandidateRepository {
    private final JPAQueryFactory queryFactory;

    public MenuAlternativeCandidateRepository(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    public Optional<MenuAlternativeSourceView> findSource(long storeId, long menuId) {
        List<BaseRow> rows = baseQuery(new BooleanBuilder()
                        .and(QStore.store.id.eq(storeId))
                        .and(QMenu.menu.id.eq(menuId)), false)
                .limit(1).fetch();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, List<String>> secondary = secondaryByVersion(rows);
        Map<Long, List<MenuAlternativeAllergenView>> allergens = allergensByVersion(rows);
        return Optional.of(sourceView(rows.getFirst(), secondary, allergens));
    }

    public List<MenuAlternativeCandidateView> findSameStoreCandidates(
            long storeId, long sourceMenuId, int limit) {
        List<BaseRow> rows = baseQuery(new BooleanBuilder().and(QStore.store.id.eq(storeId))
                        .and(QMenu.menu.id.ne(sourceMenuId)), true)
                .orderBy(QMenu.menu.id.asc()).limit(limit).fetch();
        return candidateViews(rows);
    }

    public List<MenuAlternativeCandidateView> findNearbyCandidates(long sourceStoreId,
            long sourceMenuId, BoundingBox box, int limit) {
        QStore store = QStore.store;
        BooleanBuilder where = new BooleanBuilder()
                .and(store.id.ne(sourceStoreId))
                .and(QMenu.menu.id.ne(sourceMenuId))
                .and(store.geocodingStatus.eq(GeocodingStatus.VERIFIED))
                .and(store.geocodingAddressVersion.eq(store.addressVersion))
                .and(store.latitude.between(BigDecimal.valueOf(box.minLatitude()),
                        BigDecimal.valueOf(box.maxLatitude())))
                .and(store.longitude.between(BigDecimal.valueOf(box.minLongitude()),
                        BigDecimal.valueOf(box.maxLongitude())));
        List<BaseRow> rows = baseQuery(where, true).orderBy(store.id.asc(), QMenu.menu.id.asc())
                .limit(limit).fetch();
        return candidateViews(rows);
    }

    public Optional<MenuAlternativeInterpretationText> findInterpretationText(
            long storeId,
            long menuId
    ) {
        QStore store = QStore.store;
        QMenu menu = QMenu.menu;
        QMenuVersion version = QMenuVersion.menuVersion;
        InterpretationRow row = queryFactory.select(Projections.constructor(
                        InterpretationRow.class,
                        menu.id,
                        store.id,
                        version.versionNumber,
                        version.id,
                        version.name,
                        version.description,
                        version.primaryCategoryCode))
                .from(menu)
                .join(version).on(version.menu.eq(menu))
                .join(store).on(store.id.eq(menu.storeId))
                .where(store.id.eq(storeId)
                        .and(menu.id.eq(menuId))
                        .and(store.verificationStatus.eq(VerificationStatus.APPROVED))
                        .and(store.operationStatus.ne(OperationStatus.CLOSED))
                        .and(menu.retired.isFalse())
                        .and(menu.visibility.eq(MenuVisibility.VISIBLE))
                        .and(menu.sellingStatus.in(
                                MenuSellingStatus.SELLING,
                                MenuSellingStatus.SOLD_OUT,
                                MenuSellingStatus.PAUSED))
                        .and(menu.publishedVersionNumber.eq(version.versionNumber))
                        .and(version.status.eq(MenuVersionStatus.PUBLISHED)))
                .fetchFirst();
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new MenuAlternativeInterpretationText(
                row.name(),
                row.description(),
                row.primaryCategoryCode(),
                stringValues(version, row.versionId(), false),
                stringValues(version, row.versionId(), true)));
    }

    public List<MenuAlternativeCandidateView> findSameStoreExpandedCandidates(
            long storeId,
            long sourceMenuId,
            List<String> concepts,
            int limit
    ) {
        if (concepts.isEmpty()) {
            return List.of();
        }
        NumberExpression<Integer> conceptScore = expandedConceptScore(concepts);
        List<BaseRow> rows = baseQuery(new BooleanBuilder()
                        .and(QStore.store.id.eq(storeId))
                        .and(QMenu.menu.id.ne(sourceMenuId))
                        .and(expandedConceptMatches(concepts)), true, conceptScore)
                .orderBy(conceptScore.desc(), QMenu.menu.id.asc())
                .limit(limit)
                .fetch();
        return candidateViews(rows, concepts);
    }

    public List<MenuAlternativeCandidateView> findNearbyExpandedCandidates(
            long sourceStoreId,
            long sourceMenuId,
            BoundingBox box,
            List<String> concepts,
            int limit
    ) {
        if (concepts.isEmpty()) {
            return List.of();
        }
        QStore store = QStore.store;
        BooleanBuilder where = new BooleanBuilder()
                .and(store.id.ne(sourceStoreId))
                .and(QMenu.menu.id.ne(sourceMenuId))
                .and(expandedConceptMatches(concepts))
                .and(store.geocodingStatus.eq(GeocodingStatus.VERIFIED))
                .and(store.geocodingAddressVersion.eq(store.addressVersion))
                .and(store.latitude.between(BigDecimal.valueOf(box.minLatitude()),
                        BigDecimal.valueOf(box.maxLatitude())))
                .and(store.longitude.between(BigDecimal.valueOf(box.minLongitude()),
                        BigDecimal.valueOf(box.maxLongitude())));
        NumberExpression<Integer> conceptScore = expandedConceptScore(concepts);
        return candidateViews(baseQuery(where, true, conceptScore)
                .orderBy(conceptScore.desc(), store.id.asc(), QMenu.menu.id.asc())
                .limit(limit)
                .fetch(), concepts);
    }

    private static BooleanBuilder expandedConceptMatches(List<String> concepts) {
        QMenuVersion version = QMenuVersion.menuVersion;
        BooleanBuilder matches = new BooleanBuilder();
        for (String concept : concepts) {
            String pattern = IntegratedStoreSearchPredicates.literalContainsPattern(concept);
            matches.or(version.name.likeIgnoreCase(pattern, '!')
                    .or(version.description.likeIgnoreCase(pattern, '!'))
                    .or(version.primaryCategoryCode.likeIgnoreCase(pattern, '!'))
                    .or(version.secondaryCategoryCodes.any().likeIgnoreCase(pattern, '!'))
                    .or(version.localTags.any().likeIgnoreCase(pattern, '!')));
        }
        return matches;
    }

    private static NumberExpression<Integer> expandedConceptScore(List<String> concepts) {
        NumberExpression<Integer> score = Expressions.asNumber(0);
        for (int index = concepts.size() - 1; index >= 0; index--) {
            int points = Math.max(15, 50 - index * 5);
            score = new CaseBuilder().when(expandedConceptMatch(concepts.get(index)))
                    .then(points).otherwise(score);
        }
        return score;
    }

    private static com.querydsl.core.types.dsl.BooleanExpression expandedConceptMatch(
            String concept
    ) {
        QMenuVersion version = QMenuVersion.menuVersion;
        String pattern = IntegratedStoreSearchPredicates.literalContainsPattern(concept);
        return version.name.likeIgnoreCase(pattern, '!')
                .or(version.description.likeIgnoreCase(pattern, '!'))
                .or(version.primaryCategoryCode.likeIgnoreCase(pattern, '!'));
    }

    private List<String> stringValues(
            QMenuVersion version,
            long versionId,
            boolean localTags
    ) {
        StringPath value = Expressions.stringPath(
                localTags ? "alternativeInterpretationTag" : "alternativeInterpretationCategory");
        var query = queryFactory.select(value).from(version);
        if (localTags) {
            query.join(version.localTags, value);
        } else {
            query.join(version.secondaryCategoryCodes, value);
        }
        return query.where(version.id.eq(versionId)).orderBy(value.asc()).fetch();
    }

    private com.querydsl.jpa.impl.JPAQuery<BaseRow> baseQuery(
            BooleanBuilder additional,
            boolean sellingOnly
    ) {
        return baseQuery(additional, sellingOnly, Expressions.asNumber(0));
    }

    private com.querydsl.jpa.impl.JPAQuery<BaseRow> baseQuery(
            BooleanBuilder additional,
            boolean sellingOnly,
            NumberExpression<Integer> conceptScore
    ) {
        QStore store = QStore.store;
        QMenu menu = QMenu.menu;
        QMenuVersion version = QMenuVersion.menuVersion;
        var currentCoordinates = store.geocodingStatus.eq(GeocodingStatus.VERIFIED)
                .and(store.geocodingAddressVersion.eq(store.addressVersion));
        var where = new BooleanBuilder()
                .and(store.verificationStatus.eq(VerificationStatus.APPROVED))
                .and(store.operationStatus.eq(OperationStatus.OPEN))
                .and(store.reservationEnabled.isTrue()).and(store.menuHoldEnabled.isTrue())
                .and(menu.retired.isFalse()).and(menu.visibility.eq(MenuVisibility.VISIBLE))
                .and(menu.publishedVersionNumber.eq(version.versionNumber))
                .and(version.status.eq(MenuVersionStatus.PUBLISHED))
                .and(version.holdSelectionAllowed.isTrue()).and(additional);
        if (sellingOnly) {
            where.and(menu.sellingStatus.eq(MenuSellingStatus.SELLING));
        } else {
            where.and(menu.sellingStatus.in(
                    MenuSellingStatus.SELLING,
                    MenuSellingStatus.SOLD_OUT,
                    MenuSellingStatus.PAUSED));
        }
        return queryFactory.select(Projections.constructor(BaseRow.class,
                        version.id, store.id, store.name, menu.id, version.name, version.price,
                        version.primaryCategoryCode, version.allergenInformationStatus,
                        new CaseBuilder().when(currentCoordinates).then(store.latitude)
                                .otherwise(Expressions.nullExpression(BigDecimal.class)),
                        new CaseBuilder().when(currentCoordinates).then(store.longitude)
                                .otherwise(Expressions.nullExpression(BigDecimal.class)),
                        conceptScore))
                .from(menu).join(version).on(version.menu.eq(menu))
                .join(store).on(store.id.eq(menu.storeId)).where(where);
    }

    private MenuAlternativeSourceView sourceView(BaseRow row,
            Map<Long, List<String>> secondary,
            Map<Long, List<MenuAlternativeAllergenView>> allergens) {
        return new MenuAlternativeSourceView(row.storeId(), row.storeName(), row.menuId(),
                row.menuName(), row.unitPrice(), row.primaryCategoryCode(),
                secondary.getOrDefault(row.versionId(), List.of()),
                row.allergenInformationStatus().name(),
                allergens.getOrDefault(row.versionId(), List.of()),
                row.latitude(), row.longitude());
    }

    private MenuAlternativeCandidateView candidateView(BaseRow row,
            Map<Long, List<String>> secondary,
            Map<Long, List<MenuAlternativeAllergenView>> allergens,
            int conceptScore) {
        return new MenuAlternativeCandidateView(row.storeId(), row.storeName(), row.menuId(),
                row.menuName(), row.unitPrice(), row.primaryCategoryCode(),
                secondary.getOrDefault(row.versionId(), List.of()),
                row.allergenInformationStatus().name(),
                allergens.getOrDefault(row.versionId(), List.of()),
                row.latitude(), row.longitude(), conceptScore);
    }

    private List<MenuAlternativeCandidateView> candidateViews(List<BaseRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> secondary = secondaryByVersion(rows);
        Map<Long, List<MenuAlternativeAllergenView>> allergens = allergensByVersion(rows);
        return rows.stream().map(row -> candidateView(
                row, secondary, allergens, row.conceptScore())).toList();
    }

    private List<MenuAlternativeCandidateView> candidateViews(
            List<BaseRow> rows,
            List<String> concepts
    ) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> secondary = secondaryByVersion(rows);
        Map<Long, List<String>> localTags = localTagsByVersion(rows);
        Map<Long, List<MenuAlternativeAllergenView>> allergens = allergensByVersion(rows);
        return rows.stream().map(row -> candidateView(row, secondary, allergens,
                Math.max(row.conceptScore(), collectionConceptScore(
                        concepts,
                        secondary.getOrDefault(row.versionId(), List.of()),
                        localTags.getOrDefault(row.versionId(), List.of()))))).toList();
    }

    private static int collectionConceptScore(
            List<String> concepts,
            List<String> secondaryCategories,
            List<String> localTags
    ) {
        for (int index = 0; index < concepts.size(); index++) {
            String concept = concepts.get(index).toLowerCase(java.util.Locale.ROOT);
            boolean matches = java.util.stream.Stream.concat(
                            secondaryCategories.stream(), localTags.stream())
                    .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                    .anyMatch(value -> value.contains(concept));
            if (matches) {
                return Math.max(15, 50 - index * 5);
            }
        }
        return 0;
    }

    private Map<Long, List<String>> secondaryByVersion(List<BaseRow> rows) {
        QMenuVersion version = QMenuVersion.menuVersion;
        StringPath category = Expressions.stringPath("alternativeSecondaryCategory");
        List<Long> versionIds = versionIds(rows);
        Map<Long, List<String>> result = new LinkedHashMap<>();
        queryFactory.select(version.id, category).from(version)
                .join(version.secondaryCategoryCodes, category)
                .where(version.id.in(versionIds)).orderBy(version.id.asc(), category.asc()).fetch()
                .forEach(tuple -> result.computeIfAbsent(tuple.get(version.id), ignored ->
                        new ArrayList<>()).add(tuple.get(category)));
        return result;
    }

    private Map<Long, List<MenuAlternativeAllergenView>> allergensByVersion(List<BaseRow> rows) {
        QMenuVersion version = QMenuVersion.menuVersion;
        QAllergenDisclosure allergen = new QAllergenDisclosure("alternativeAllergen");
        List<Long> versionIds = versionIds(rows);
        Map<Long, List<MenuAlternativeAllergenView>> result = new LinkedHashMap<>();
        queryFactory.select(version.id, allergen.ingredientCode, allergen.status).from(version)
                .join(version.allergenDisclosures, allergen).where(version.id.in(versionIds))
                .orderBy(version.id.asc(), allergen.ingredientCode.asc()).fetch()
                .forEach(tuple -> result.computeIfAbsent(tuple.get(version.id), ignored ->
                        new ArrayList<>()).add(new MenuAlternativeAllergenView(
                                tuple.get(allergen.ingredientCode).name(),
                                tuple.get(allergen.status).name())));
        return result;
    }

    private Map<Long, List<String>> localTagsByVersion(List<BaseRow> rows) {
        QMenuVersion version = QMenuVersion.menuVersion;
        StringPath tag = Expressions.stringPath("alternativeLocalTag");
        List<Long> versionIds = versionIds(rows);
        Map<Long, List<String>> result = new LinkedHashMap<>();
        queryFactory.select(version.id, tag).from(version)
                .join(version.localTags, tag)
                .where(version.id.in(versionIds)).orderBy(version.id.asc(), tag.asc()).fetch()
                .forEach(tuple -> result.computeIfAbsent(tuple.get(version.id), ignored ->
                        new ArrayList<>()).add(tuple.get(tag)));
        return result;
    }

    private static List<Long> versionIds(List<BaseRow> rows) {
        return rows.stream().map(BaseRow::versionId).distinct().toList();
    }

    public record BaseRow(long versionId, long storeId, String storeName, long menuId,
            String menuName, int unitPrice, String primaryCategoryCode,
            com.miriyum.domain.menu.model.DisclosureRegistrationStatus allergenInformationStatus,
            BigDecimal latitude, BigDecimal longitude, int conceptScore) {}

    public record InterpretationRow(
            long menuId,
            long storeId,
            int versionNumber,
            long versionId,
            String name,
            String description,
            String primaryCategoryCode
    ) {}

    public record MenuAlternativeInterpretationText(
            String name,
            String description,
            String primaryCategoryCode,
            List<String> secondaryCategoryCodes,
            List<String> localTags
    ) {
        public MenuAlternativeInterpretationText {
            secondaryCategoryCodes = List.copyOf(secondaryCategoryCodes);
            localTags = List.copyOf(localTags);
        }
    }
}
