package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuditEvent;
import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditSearchRequest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 감사 원장에 append와 읽기만 노출하며 UPDATE·DELETE API를 의도적으로 제공하지 않는다. */
@Repository
public class PlatformOperatorAuditEventRepository {

    private final EntityManager entityManager;
    private final NamedParameterJdbcTemplate jdbc;

    public PlatformOperatorAuditEventRepository(
            EntityManager entityManager,
            NamedParameterJdbcTemplate jdbc
    ) {
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    public PlatformOperatorAuditEvent append(PlatformOperatorAuditEvent event) {
        entityManager.persist(event);
        entityManager.flush();
        return event;
    }

    public Optional<PlatformOperatorAuditEvent> findById(long id) {
        return Optional.ofNullable(entityManager.find(PlatformOperatorAuditEvent.class, id));
    }

    public boolean existsCorrection(String source, long sourceId) {
        return !entityManager.createQuery("""
                select event.id from PlatformOperatorAuditEvent event
                 where event.originalEventSource = :source
                   and event.originalEventId = :sourceId
                """, Long.class)
                .setParameter("source", source)
                .setParameter("sourceId", sourceId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    public List<PlatformOperatorAuditEvent> findCorrections(String source, long sourceId) {
        return entityManager.createQuery("""
                select event from PlatformOperatorAuditEvent event
                 where event.originalEventSource = :source
                   and event.originalEventId = :sourceId
                 order by event.occurredAt, event.id
                """, PlatformOperatorAuditEvent.class)
                .setParameter("source", source)
                .setParameter("sourceId", sourceId)
                .getResultList();
    }

    public AuditPage search(PlatformOperatorAuditSearchRequest request) {
        StringBuilder where = new StringBuilder(" where 1=1");
        Map<String, Object> parameters = new HashMap<>();
        add(where, parameters, "source", request.source(), "source = :source");
        add(where, parameters, "action", request.action() == null ? null : request.action().name(),
                "action = :action");
        add(where, parameters, "outcome", request.outcome() == null ? null : request.outcome().name(),
                "outcome = :outcome");
        add(where, parameters, "actorId", request.actorOperatorId(), "actor_id = :actorId");
        add(where, parameters, "targetType", request.targetType(), "target_type = :targetType");
        add(where, parameters, "targetId", request.targetId(), "target_id = :targetId");
        if (request.occurredFrom() != null) {
            where.append(" and occurred_at >= :occurredFrom");
            parameters.put("occurredFrom", request.occurredFrom());
        }
        if (request.occurredTo() != null) {
            where.append(" and occurred_at <= :occurredTo");
            parameters.put("occurredTo", request.occurredTo());
        }
        add(where, parameters, "originalEventKey", request.originalEventKey(),
                "original_event_key = :originalEventKey");

        String filtered = "select * from (" + UNION_PROJECTION + ") audit" + where;
        Long total = jdbc.queryForObject("select count(*) from (" + filtered + ") counted", parameters, Long.class);
        parameters.put("limit", request.size());
        parameters.put("offset", (long) request.page() * request.size());
        List<AuditRow> rows = jdbc.query(filtered
                        + " order by occurred_at desc, source desc, numeric_id desc limit :limit offset :offset",
                parameters,
                (resultSet, rowNumber) -> row(
                        resultSet.getString("event_key"), resultSet.getString("source"),
                        resultSet.getString("action"), resultSet.getString("outcome"),
                        resultSet.getString("actor_id"), resultSet.getLong("authority_version"),
                        resultSet.getString("roles"), resultSet.getString("permissions"),
                        resultSet.getString("before_status"), resultSet.getString("after_status"),
                        resultSet.getString("before_roles"), resultSet.getString("after_roles"),
                        resultSet.getString("before_permissions"), resultSet.getString("after_permissions"),
                        resultSet.getString("target_type"), resultSet.getString("target_id"),
                        resultSet.getString("reason"), resultSet.getString("correlation_id"),
                        resultSet.getString("corrected_action"), resultSet.getString("corrected_outcome"),
                        resultSet.getString("corrected_target_type"), resultSet.getString("corrected_target_id"),
                        resultSet.getString("corrected_reason"),
                        resultSet.getString("original_event_key"),
                        resultSet.getTimestamp("occurred_at").toInstant()));
        return new AuditPage(rows, total == null ? 0L : total);
    }

    public Optional<AuditRow> findProjected(String eventKey) {
        String source = eventKey.substring(0, eventKey.indexOf(':'));
        long numericId = Long.parseLong(eventKey.substring(eventKey.indexOf(':') + 1));
        List<AuditRow> rows = jdbc.query(
                "select * from (" + UNION_PROJECTION + ") audit where source = :source and numeric_id = :numericId",
                Map.of("source", source, "numericId", numericId),
                (resultSet, rowNumber) -> row(
                        resultSet.getString("event_key"), resultSet.getString("source"),
                        resultSet.getString("action"), resultSet.getString("outcome"),
                        resultSet.getString("actor_id"), resultSet.getLong("authority_version"),
                        resultSet.getString("roles"), resultSet.getString("permissions"),
                        resultSet.getString("before_status"), resultSet.getString("after_status"),
                        resultSet.getString("before_roles"), resultSet.getString("after_roles"),
                        resultSet.getString("before_permissions"), resultSet.getString("after_permissions"),
                        resultSet.getString("target_type"), resultSet.getString("target_id"),
                        resultSet.getString("reason"), resultSet.getString("correlation_id"),
                        resultSet.getString("corrected_action"), resultSet.getString("corrected_outcome"),
                        resultSet.getString("corrected_target_type"), resultSet.getString("corrected_target_id"),
                        resultSet.getString("corrected_reason"),
                        resultSet.getString("original_event_key"),
                        resultSet.getTimestamp("occurred_at").toInstant()));
        return rows.stream().findFirst();
    }

    private static void add(
            StringBuilder where,
            Map<String, Object> parameters,
            String name,
            Object value,
            String clause
    ) {
        if (value != null) {
            where.append(" and ").append(clause);
            parameters.put(name, value);
        }
    }

    private static AuditRow row(
            String eventKey,
            String source,
            String action,
            String outcome,
            String actorId,
            long authorityVersion,
            String roles,
            String permissions,
            String beforeStatus,
            String afterStatus,
            String beforeRoles,
            String afterRoles,
            String beforePermissions,
            String afterPermissions,
            String targetType,
            String targetId,
            String reason,
            String correlationId,
            String correctedAction,
            String correctedOutcome,
            String correctedTargetType,
            String correctedTargetId,
            String correctedReason,
            String originalEventKey,
            Instant occurredAt
    ) {
        return new AuditRow(eventKey, source, action, outcome, actorId, authorityVersion,
                jsonArray(roles), jsonArray(permissions), beforeStatus, afterStatus,
                jsonArray(beforeRoles), jsonArray(afterRoles), jsonArray(beforePermissions),
                jsonArray(afterPermissions), targetType, targetId, reason, correctedAction,
                correctedOutcome, correctedTargetType, correctedTargetId, correctedReason,
                correlationId, originalEventKey, occurredAt);
    }

    private static Set<String> jsonArray(String json) {
        if (json == null || json.equals("[]")) return Set.of();
        String content = json.substring(1, json.length() - 1).trim();
        if (content.isEmpty()) return Set.of();
        Set<String> values = new LinkedHashSet<>();
        for (String value : content.split(",")) {
            values.add(value.trim().replaceAll("^\\\"|\\\"$", ""));
        }
        return Set.copyOf(values);
    }

    public record AuditPage(List<AuditRow> content, long totalElements) {
        public AuditPage {
            content = List.copyOf(content);
        }
    }

    public record AuditRow(
            String eventKey,
            String source,
            String action,
            String outcome,
            String actorId,
            long authorityVersion,
            Set<String> roles,
            Set<String> permissions,
            String beforeStatus,
            String afterStatus,
            Set<String> beforeRoles,
            Set<String> afterRoles,
            Set<String> beforePermissions,
            Set<String> afterPermissions,
            String targetType,
            String targetId,
            String reason,
            String correctedAction,
            String correctedOutcome,
            String correctedTargetType,
            String correctedTargetId,
            String correctedReason,
            String correlationId,
            String originalEventKey,
            Instant occurredAt
    ) {
        public AuditRow {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
            beforeRoles = Set.copyOf(beforeRoles);
            afterRoles = Set.copyOf(afterRoles);
            beforePermissions = Set.copyOf(beforePermissions);
            afterPermissions = Set.copyOf(afterPermissions);
        }
    }

    private static final String UNION_PROJECTION = """
            select concat('AUTH:', platform_operator_auth_event_id) event_key,
                   'AUTH' source,
                   platform_operator_auth_event_id numeric_id,
                   event_type action,
                   case when outcome = 'FAILURE' then 'FAILED' else outcome end outcome,
                   cast(platform_operator_account_id as char) actor_id,
                   authority_version,
                   json_array() roles,
                   json_array() permissions,
                   null before_status,
                   null after_status,
                   json_array() before_roles,
                   json_array() after_roles,
                   json_array() before_permissions,
                   json_array() after_permissions,
                   'PLATFORM_OPERATOR_ACCOUNT' target_type,
                   cast(platform_operator_account_id as char) target_id,
                   'AUTHENTICATION_EVENT' reason,
                   event_key correlation_id,
                   null corrected_action,
                   null corrected_outcome,
                   null corrected_target_type,
                   null corrected_target_id,
                   null corrected_reason,
                   null original_event_key,
                   occurred_at
              from platform_operator_auth_events
            union all
            select concat('ADMIN:', platform_operator_audit_event_id) event_key,
                   'ADMIN' source,
                   platform_operator_audit_event_id numeric_id,
                   action,
                   outcome,
                   cast(actor_platform_operator_account_id as char) actor_id,
                   actor_authority_version authority_version,
                   actor_roles roles,
                   actor_permissions permissions,
                   before_status,
                   after_status,
                   before_roles,
                   after_roles,
                   before_permissions,
                   after_permissions,
                   target_type,
                   target_id,
                   reason,
                   correlation_id,
                   corrected_action,
                   corrected_outcome,
                   corrected_target_type,
                   corrected_target_id,
                   corrected_reason,
                   case when original_event_id is null then null
                        else concat(original_event_source, ':', original_event_id) end original_event_key,
                   occurred_at
              from platform_operator_audit_events
            """;
}
