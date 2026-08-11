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
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MenuAlternativeCandidateRepository {
    private final JPAQueryFactory queryFactory;

    public MenuAlternativeCandidateRepository(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    public Optional<MenuAlternativeSourceView> findSource(long storeId, long menuId) {
        return Optional.ofNullable(baseQuery(new BooleanBuilder()
                        .and(QStore.store.id.eq(storeId))
                        .and(QMenu.menu.id.eq(menuId)))
                .fetchFirst()).map(this::sourceView);
    }

    public List<MenuAlternativeCandidateView> findSameStoreCandidates(
            long storeId, long sourceMenuId, int limit) {
        return baseQuery(new BooleanBuilder().and(QStore.store.id.eq(storeId))
                        .and(QMenu.menu.id.ne(sourceMenuId)))
                .orderBy(QMenu.menu.id.asc()).limit(limit).fetch().stream()
                .map(this::candidateView).toList();
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
        return baseQuery(where).orderBy(store.id.asc(), QMenu.menu.id.asc())
                .limit(limit).fetch().stream().map(this::candidateView).toList();
    }

    private com.querydsl.jpa.impl.JPAQuery<BaseRow> baseQuery(BooleanBuilder additional) {
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
                .and(menu.sellingStatus.eq(MenuSellingStatus.SELLING))
                .and(menu.publishedVersionNumber.eq(version.versionNumber))
                .and(version.status.eq(MenuVersionStatus.PUBLISHED))
                .and(version.holdSelectionAllowed.isTrue()).and(additional);
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

    private MenuAlternativeSourceView sourceView(BaseRow row) {
        return new MenuAlternativeSourceView(row.storeId(), row.storeName(), row.menuId(),
                row.menuName(), row.unitPrice(), row.primaryCategoryCode(),
                secondary(row.versionId()), row.allergenInformationStatus().name(),
                allergens(row.versionId()), row.latitude(), row.longitude());
    }

    private MenuAlternativeCandidateView candidateView(BaseRow row) {
        return new MenuAlternativeCandidateView(row.storeId(), row.storeName(), row.menuId(),
                row.menuName(), row.unitPrice(), row.primaryCategoryCode(),
                secondary(row.versionId()), row.allergenInformationStatus().name(),
                allergens(row.versionId()), row.latitude(), row.longitude());
    }

    private List<String> secondary(long versionId) {
        QMenuVersion version = QMenuVersion.menuVersion;
        StringPath category = Expressions.stringPath("alternativeSecondaryCategory");
        return queryFactory.select(category).from(version)
                .join(version.secondaryCategoryCodes, category)
                .where(version.id.eq(versionId)).orderBy(category.asc()).fetch();
    }

    private List<MenuAlternativeAllergenView> allergens(long versionId) {
        QMenuVersion version = QMenuVersion.menuVersion;
        QAllergenDisclosure allergen = new QAllergenDisclosure("alternativeAllergen");
        return queryFactory.select(allergen.ingredientCode, allergen.status).from(version)
                .join(version.allergenDisclosures, allergen).where(version.id.eq(versionId))
                .orderBy(allergen.ingredientCode.asc()).fetch().stream()
                .map(tuple -> new MenuAlternativeAllergenView(
                        tuple.get(allergen.ingredientCode).name(),
                        tuple.get(allergen.status).name())).toList();
    }

    public record BaseRow(long versionId, long storeId, String storeName, long menuId,
            String menuName, int unitPrice, String primaryCategoryCode,
            com.miriyum.domain.menu.model.DisclosureRegistrationStatus allergenInformationStatus,
            BigDecimal latitude, BigDecimal longitude) {}
}
