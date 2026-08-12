package com.miriyum.domain.search.repository;

import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.search.dto.publicapi.PublicMenu;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Reads only fields that are part of the public store contract. */
@Repository
public class StorePublicReadRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StorePublicReadRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PublicStoreSnapshot> findPublicStore(long storeId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("storeId", storeId);
        List<PublicStoreSnapshot> stores = jdbcTemplate.query("""
                SELECT store_id, name, description, region, address, time_zone_id,
                       store_category_code, operation_status,
                       reservation_enabled, menu_hold_enabled, pickup_enabled
                FROM stores
                WHERE store_id = :storeId
                  AND verification_status = 'APPROVED'
                  AND operation_status <> 'CLOSED'
                """, parameters, (resultSet, rowNumber) -> new PublicStoreSnapshot(
                resultSet.getLong("store_id"), resultSet.getString("name"),
                resultSet.getString("description"),
                Region.valueOf(resultSet.getString("region")),
                resultSet.getString("address"), resultSet.getString("time_zone_id"),
                resultSet.getString("store_category_code"), List.of(),
                OperationStatus.valueOf(resultSet.getString("operation_status")),
                resultSet.getBoolean("reservation_enabled"),
                resultSet.getBoolean("menu_hold_enabled"),
                resultSet.getBoolean("pickup_enabled")));
        if (stores.isEmpty()) {
            return Optional.empty();
        }
        List<String> tags = jdbcTemplate.queryForList("""
                SELECT tag_code
                FROM store_tag_assignment
                WHERE store_id = :storeId
                ORDER BY tag_code
                """, parameters, String.class);
        PublicStoreSnapshot store = stores.getFirst();
        return Optional.of(new PublicStoreSnapshot(
                store.storeId(), store.name(), store.description(), store.region(),
                store.address(), store.timeZoneId(), store.storeCategoryCode(), tags,
                store.operationStatus(),
                store.reservationEnabled(), store.menuHoldEnabled(), store.pickupEnabled()));
    }

    public List<PublicMenu> findPublicMenus(long storeId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("storeId", storeId);
        List<MenuRow> rows = jdbcTemplate.query("""
                SELECT m.menu_id, m.selling_status, mv.menu_version_id, mv.name,
                       mv.description, mv.price,
                       CASE WHEN rme.menu_id IS NULL THEN FALSE ELSE TRUE END AS representative,
                       mv.primary_category_code, mv.hold_selection_allowed,
                       mv.pickup_selection_allowed
                FROM stores s
                JOIN menus m ON m.store_id = s.store_id
                JOIN menu_versions mv
                  ON mv.menu_id = m.menu_id
                 AND mv.version_number = m.published_version_number
                 AND mv.status = 'PUBLISHED'
                LEFT JOIN representative_menu_entries rme
                  ON rme.store_id = m.store_id
                 AND rme.menu_id = m.menu_id
                 AND m.selling_status IN ('SELLING', 'SOLD_OUT')
                WHERE s.store_id = :storeId
                  AND s.verification_status = 'APPROVED'
                  AND s.operation_status <> 'CLOSED'
                  AND m.retired = FALSE
                  AND m.visibility = 'VISIBLE'
                ORDER BY m.menu_id
                """, parameters, StorePublicReadRepository::mapMenuRow);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> versionIds = rows.stream().map(MenuRow::versionId).toList();
        Map<Long, List<String>> secondary = valuesByVersion(
                "menu_version_secondary_categories", "category_code", versionIds);
        Map<Long, List<String>> localTags = valuesByVersion(
                "menu_version_local_tags", "tag_value", versionIds);
        return rows.stream().map(row -> new PublicMenu(
                Long.toString(row.menuId()), row.name(), row.description(), row.price(),
                row.representative(), row.primaryCategoryCode(),
                secondary.getOrDefault(row.versionId(), List.of()),
                localTags.getOrDefault(row.versionId(), List.of()),
                row.holdEnabled(), row.pickupEnabled(), row.sellingStatus())).toList();
    }

    private Map<Long, List<String>> valuesByVersion(
            String table,
            String valueColumn,
            List<Long> versionIds
    ) {
        String sql = "SELECT menu_version_id, " + valueColumn + " AS value FROM " + table
                + " WHERE menu_version_id IN (:versionIds)"
                + " ORDER BY menu_version_id, sort_order";
        Map<Long, List<String>> values = new LinkedHashMap<>();
        jdbcTemplate.query(sql, new MapSqlParameterSource("versionIds", versionIds),
                (RowCallbackHandler) resultSet ->
                values.computeIfAbsent(resultSet.getLong("menu_version_id"),
                                ignored -> new ArrayList<>())
                        .add(resultSet.getString("value")));
        return values;
    }

    private static MenuRow mapMenuRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MenuRow(
                resultSet.getLong("menu_id"), resultSet.getLong("menu_version_id"),
                resultSet.getString("name"), resultSet.getString("description"),
                resultSet.getLong("price"), resultSet.getBoolean("representative"),
                resultSet.getString("primary_category_code"),
                resultSet.getBoolean("hold_selection_allowed"),
                resultSet.getBoolean("pickup_selection_allowed"),
                MenuSellingStatus.valueOf(resultSet.getString("selling_status")));
    }

    private record MenuRow(
            long menuId,
            long versionId,
            String name,
            String description,
            long price,
            boolean representative,
            String primaryCategoryCode,
            boolean holdEnabled,
            boolean pickupEnabled,
            MenuSellingStatus sellingStatus
    ) {
    }
}
