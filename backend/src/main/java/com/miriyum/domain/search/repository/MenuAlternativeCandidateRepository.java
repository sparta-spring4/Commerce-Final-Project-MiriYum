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
import com.miriyum.domain.search.semantic.SemanticMenuDocument;
import com.miriyum.domain.search.semantic.SemanticMenuDocumentSource;
import com.miriyum.domain.search.semantic.SemanticMenuHit;
import com.miriyum.domain.store.entity.QStore;
import com.miriyum.domain.store.enums.GeocodingStatus;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
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
public class MenuAlternativeCandidateRepository implements SemanticMenuDocumentSource {
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

    public List<MenuAlternativeCandidateView> findSameStoreSemanticCandidates(
            long storeId,
            long sourceMenuId,
            List<SemanticMenuHit> hits,
            int limit
    ) {
        if (hits.isEmpty()) {
            return List.of();
        }
        List<BaseRow> rows = baseQuery(new BooleanBuilder()
                        .and(QStore.store.id.eq(storeId))
                        .and(QMenu.menu.id.ne(sourceMenuId))
                        .and(currentSemanticCoordinates(hits)), true)
                .limit(limit).fetch();
        return orderSemantic(candidateViews(rows), hits, limit);
    }

    public List<MenuAlternativeCandidateView> findNearbySemanticCandidates(
            long sourceStoreId,
            long sourceMenuId,
            BoundingBox box,
            List<SemanticMenuHit> hits,
            int limit
    ) {
        if (hits.isEmpty()) {
            return List.of();
        }
        QStore store = QStore.store;
        BooleanBuilder where = new BooleanBuilder()
                .and(store.id.ne(sourceStoreId))
                .and(QMenu.menu.id.ne(sourceMenuId))
                .and(currentSemanticCoordinates(hits))
                .and(store.geocodingStatus.eq(GeocodingStatus.VERIFIED))
                .and(store.geocodingAddressVersion.eq(store.addressVersion))
                .and(store.latitude.between(BigDecimal.valueOf(box.minLatitude()),
                        BigDecimal.valueOf(box.maxLatitude())))
                .and(store.longitude.between(BigDecimal.valueOf(box.minLongitude()),
                        BigDecimal.valueOf(box.maxLongitude())));
        return orderSemantic(candidateViews(baseQuery(where, true).limit(limit).fetch()),
                hits, limit);
    }

    private static List<MenuAlternativeCandidateView> orderSemantic(
            List<MenuAlternativeCandidateView> candidates,
            List<SemanticMenuHit> hits,
            int limit
    ) {
        Map<Long, Integer> rankByMenu = new LinkedHashMap<>();
        hits.forEach(hit -> rankByMenu.putIfAbsent(hit.menuId(), rankByMenu.size()));
        return candidates.stream()
                .sorted(java.util.Comparator.comparingInt(candidate ->
                        rankByMenu.getOrDefault(candidate.menuId(), Integer.MAX_VALUE)))
                .limit(limit)
                .toList();
    }

    private static BooleanBuilder currentSemanticCoordinates(List<SemanticMenuHit> hits) {
        BooleanBuilder coordinates = new BooleanBuilder();
        for (SemanticMenuHit hit : hits) {
            coordinates.or(QMenu.menu.id.eq(hit.menuId())
                    .and(QStore.store.id.eq(hit.storeId()))
                    .and(QMenuVersion.menuVersion.versionNumber.eq(hit.versionNumber())));
        }
        return coordinates;
    }

    @Override
    public Optional<SemanticMenuDocument> findCurrent(long menuId) {
        List<SemanticRow> rows = semanticRows(QMenu.menu.id.eq(menuId), 1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(semanticDocuments(rows).getFirst());
    }

    @Override
    public List<SemanticMenuDocument> findBatchAfter(long menuId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return semanticDocuments(semanticRows(QMenu.menu.id.gt(menuId), limit));
    }

    private List<SemanticRow> semanticRows(
            com.querydsl.core.types.Predicate additional,
            int limit
    ) {
        QStore store = QStore.store;
        QMenu menu = QMenu.menu;
        QMenuVersion version = QMenuVersion.menuVersion;
        return queryFactory.select(Projections.constructor(
                        SemanticRow.class,
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
                .where(store.verificationStatus.eq(VerificationStatus.APPROVED)
                        .and(store.operationStatus.ne(OperationStatus.CLOSED))
                        .and(menu.retired.isFalse())
                        .and(menu.visibility.eq(MenuVisibility.VISIBLE))
                        .and(menu.sellingStatus.in(
                                MenuSellingStatus.SELLING,
                                MenuSellingStatus.SOLD_OUT))
                        .and(menu.publishedVersionNumber.eq(version.versionNumber))
                        .and(version.status.eq(MenuVersionStatus.PUBLISHED))
                        .and(additional))
                .orderBy(menu.id.asc())
                .limit(limit)
                .fetch();
    }

    private List<SemanticMenuDocument> semanticDocuments(List<SemanticRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        QMenuVersion version = QMenuVersion.menuVersion;
        StringPath secondary = Expressions.stringPath("semanticSecondaryCategory");
        StringPath localTag = Expressions.stringPath("semanticLocalTag");
        List<Long> versionIds = rows.stream().map(SemanticRow::versionId).toList();
        Map<Long, List<String>> secondaryByVersion = new LinkedHashMap<>();
        queryFactory.select(version.id, secondary).from(version)
                .join(version.secondaryCategoryCodes, secondary)
                .where(version.id.in(versionIds))
                .orderBy(version.id.asc(), secondary.asc())
                .fetch()
                .forEach(tuple -> secondaryByVersion.computeIfAbsent(
                        tuple.get(version.id), ignored -> new ArrayList<>())
                        .add(tuple.get(secondary)));
        Map<Long, List<String>> tagsByVersion = new LinkedHashMap<>();
        queryFactory.select(version.id, localTag).from(version)
                .join(version.localTags, localTag)
                .where(version.id.in(versionIds))
                .orderBy(version.id.asc(), localTag.asc())
                .fetch()
                .forEach(tuple -> tagsByVersion.computeIfAbsent(
                        tuple.get(version.id), ignored -> new ArrayList<>())
                        .add(tuple.get(localTag)));
        return rows.stream().map(row -> new SemanticMenuDocument(
                row.menuId(),
                row.storeId(),
                row.versionNumber(),
                semanticText(row,
                        secondaryByVersion.getOrDefault(row.versionId(), List.of()),
                        tagsByVersion.getOrDefault(row.versionId(), List.of()))))
                .toList();
    }

    private static String semanticText(
            SemanticRow row,
            List<String> secondaryCategories,
            List<String> localTags
    ) {
        List<String> parts = new ArrayList<>();
        parts.add(row.name());
        parts.add(row.description());
        parts.add(row.primaryCategoryCode());
        parts.addAll(secondaryCategories);
        parts.addAll(localTags);
        return parts.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private com.querydsl.jpa.impl.JPAQuery<BaseRow> baseQuery(
            BooleanBuilder additional,
            boolean sellingOnly
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
                                .otherwise(Expressions.nullExpression(BigDecimal.class))))
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
            Map<Long, List<MenuAlternativeAllergenView>> allergens) {
        return new MenuAlternativeCandidateView(row.storeId(), row.storeName(), row.menuId(),
                row.menuName(), row.unitPrice(), row.primaryCategoryCode(),
                secondary.getOrDefault(row.versionId(), List.of()),
                row.allergenInformationStatus().name(),
                allergens.getOrDefault(row.versionId(), List.of()),
                row.latitude(), row.longitude());
    }

    private List<MenuAlternativeCandidateView> candidateViews(List<BaseRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> secondary = secondaryByVersion(rows);
        Map<Long, List<MenuAlternativeAllergenView>> allergens = allergensByVersion(rows);
        return rows.stream().map(row -> candidateView(row, secondary, allergens)).toList();
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

    private static List<Long> versionIds(List<BaseRow> rows) {
        return rows.stream().map(BaseRow::versionId).distinct().toList();
    }

    public record BaseRow(long versionId, long storeId, String storeName, long menuId,
            String menuName, int unitPrice, String primaryCategoryCode,
            com.miriyum.domain.menu.model.DisclosureRegistrationStatus allergenInformationStatus,
            BigDecimal latitude, BigDecimal longitude) {}

    public record SemanticRow(
            long menuId,
            long storeId,
            int versionNumber,
            long versionId,
            String name,
            String description,
            String primaryCategoryCode
    ) {}
}
