package com.miriyum.domain.store.search.repository;

import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.store.search.model.StoreSearchQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 공개 가능한 매장 후보를 MySQL의 고정 검색 정책으로 조회한다.
 */
@Repository
public class StoreSearchRepository {

    private static final String SELECT_COLUMNS = """
            SELECT s.store_id,
                   s.name,
                   s.region,
                   s.address,
                   s.store_category_code,
                   s.operation_status,
                   s.reservation_enabled,
                   s.menu_hold_enabled,
                   s.pickup_enabled,
                   s.created_at
            """;

    private static final String PUBLIC_SEARCH_PREDICATE = """
            FROM stores s
            WHERE s.verification_status = 'APPROVED'
              AND s.operation_status <> 'CLOSED'
              AND (:region IS NULL OR s.region = :region)
              AND (:categoryCode IS NULL OR s.store_category_code = :categoryCode)
              AND (
                  :keywordPattern IS NULL
                  OR LOWER(s.name) LIKE :keywordPattern ESCAPE '!'
                  OR LOWER(CASE s.region
                      WHEN 'SEOUL' THEN '서울'
                      WHEN 'BUSAN' THEN '부산'
                      WHEN 'DAEGU' THEN '대구'
                      WHEN 'DAEJEON' THEN '대전'
                      WHEN 'GWANGJU' THEN '광주'
                  END) LIKE :keywordPattern ESCAPE '!'
                  OR EXISTS (
                      SELECT 1
                      FROM menus m
                      JOIN menu_versions mv
                        ON mv.menu_id = m.menu_id
                       AND mv.version_number = m.published_version_number
                      WHERE m.store_id = s.store_id
                        AND m.retired = FALSE
                        AND m.visibility = 'VISIBLE'
                        AND mv.status = 'PUBLISHED'
                        AND LOWER(mv.name) LIKE :keywordPattern ESCAPE '!'
                  )
              )
            """;

    private static final RowMapper<StoreSearchCandidate> CANDIDATE_ROW_MAPPER =
            StoreSearchRepository::mapCandidate;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StoreSearchRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 승인됐고 폐점하지 않은 매장을 공개 필터와 고정 정렬에 따라 페이지 조회한다.
     *
     * <p>검색어의 와일드카드 escaping과 정렬 허용 목록은 {@link StoreSearchQuery}가
     * 이미 검증한 값을 사용한다. 예약 가용성은 이 단계에서 계산하지 않는다.</p>
     *
     * @param query 정규화와 검증을 마친 공개 매장 검색 조건
     * @return 중복 없는 매장 후보 페이지와 동일 조건의 전체 개수
     */
    public Page<StoreSearchCandidate> search(StoreSearchQuery query) {
        MapSqlParameterSource parameters = parameters(query);
        String dataSql = SELECT_COLUMNS
                + PUBLIC_SEARCH_PREDICATE
                + " ORDER BY " + query.sort().orderByClause()
                + " LIMIT :limit OFFSET :offset";
        List<StoreSearchCandidate> content = jdbcTemplate.query(
                dataSql, parameters, CANDIDATE_ROW_MAPPER);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + PUBLIC_SEARCH_PREDICATE,
                parameters,
                Long.class);
        return new PageImpl<>(
                content,
                PageRequest.of(query.page(), query.size()),
                total == null ? 0 : total);
    }

    /**
     * 가용성 필터링에 사용할 후보 필드와 고정 정렬을 한 SQL statement에서 확정한다.
     */
    public List<StoreSearchCandidate> searchAll(
            StoreSearchQuery query,
            int candidateLimit
    ) {
        if (candidateLimit < 1 || candidateLimit > StoreSearchCandidateLimit.HARD_MAXIMUM) {
            throw new IllegalArgumentException(
                    "candidate limit must be between 1 and 5000");
        }
        String sql = SELECT_COLUMNS
                + PUBLIC_SEARCH_PREDICATE
                + " ORDER BY " + query.sort().orderByClause()
                + " LIMIT :candidateLimit";
        MapSqlParameterSource parameters = parameters(query)
                .addValue("candidateLimit", candidateLimit);
        return jdbcTemplate.query(sql, parameters, CANDIDATE_ROW_MAPPER);
    }

    /**
     * 응답 직전에 여전히 공개 가능한 매장만 입력 순서대로 남긴다.
     */
    public List<Long> retainCurrentlyPublic(List<Long> storeIds) {
        if (storeIds == null || storeIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("positive store IDs are required");
        }
        if (storeIds.isEmpty()) {
            return List.of();
        }
        List<Long> current = jdbcTemplate.queryForList("""
                SELECT store_id
                FROM stores
                WHERE store_id IN (:storeIds)
                  AND verification_status = 'APPROVED'
                  AND operation_status <> 'CLOSED'
                """, new MapSqlParameterSource("storeIds", storeIds), Long.class);
        Set<Long> currentSet = new HashSet<>(current);
        return storeIds.stream().filter(currentSet::contains).toList();
    }

    /**
     * 후보 필터·정렬 필드는 보존하고 현재 공개 여부와 모드 상태만 다시 읽는다.
     */
    public List<StoreSearchCandidate> refreshCurrentlyPublic(
            List<StoreSearchCandidate> candidates
    ) {
        if (candidates == null || candidates.stream().anyMatch(candidate -> candidate == null)) {
            throw new IllegalArgumentException("candidates are required");
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> storeIds = candidates.stream().map(StoreSearchCandidate::storeId).toList();
        List<CurrentPublicState> current = jdbcTemplate.query("""
                SELECT store_id, operation_status, reservation_enabled,
                       menu_hold_enabled, pickup_enabled
                FROM stores
                WHERE store_id IN (:storeIds)
                  AND verification_status = 'APPROVED'
                  AND operation_status <> 'CLOSED'
                """, new MapSqlParameterSource("storeIds", storeIds),
                (resultSet, rowNumber) -> new CurrentPublicState(
                        resultSet.getLong("store_id"),
                        OperationStatus.valueOf(resultSet.getString("operation_status")),
                        resultSet.getBoolean("reservation_enabled"),
                        resultSet.getBoolean("menu_hold_enabled"),
                        resultSet.getBoolean("pickup_enabled")));
        Map<Long, CurrentPublicState> byId = new LinkedHashMap<>();
        current.forEach(state -> byId.put(state.storeId(), state));
        return candidates.stream()
                .map(candidate -> refreshSafetyState(candidate, byId.get(candidate.storeId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static StoreSearchCandidate refreshSafetyState(
            StoreSearchCandidate candidate,
            CurrentPublicState current
    ) {
        if (current == null) {
            return null;
        }
        return new StoreSearchCandidate(
                candidate.storeId(),
                candidate.name(),
                candidate.region(),
                candidate.address(),
                candidate.storeCategoryCode(),
                current.operationStatus(),
                current.reservationEnabled(),
                current.menuHoldEnabled(),
                current.pickupEnabled(),
                candidate.createdAt());
    }

    private static MapSqlParameterSource parameters(StoreSearchQuery query) {
        return new MapSqlParameterSource()
                .addValue("region", query.region() == null ? null : query.region().name())
                .addValue("categoryCode", query.storeCategoryCode())
                .addValue("keywordPattern", query.likePattern())
                .addValue("limit", query.size())
                .addValue("offset", (long) query.page() * query.size());
    }

    private static StoreSearchCandidate mapCandidate(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StoreSearchCandidate(
                resultSet.getLong("store_id"),
                resultSet.getString("name"),
                Region.valueOf(resultSet.getString("region")),
                resultSet.getString("address"),
                resultSet.getString("store_category_code"),
                OperationStatus.valueOf(resultSet.getString("operation_status")),
                resultSet.getBoolean("reservation_enabled"),
                resultSet.getBoolean("menu_hold_enabled"),
                resultSet.getBoolean("pickup_enabled"),
                resultSet.getTimestamp("created_at").toLocalDateTime());
    }

    private record CurrentPublicState(
            long storeId,
            OperationStatus operationStatus,
            boolean reservationEnabled,
            boolean menuHoldEnabled,
            boolean pickupEnabled
    ) {
    }
}
