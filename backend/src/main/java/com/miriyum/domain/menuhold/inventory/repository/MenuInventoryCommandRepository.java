package com.miriyum.domain.menuhold.inventory.repository;

import com.miriyum.domain.menuhold.inventory.model.InventoryLedgerOperation;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MenuInventoryCommandRepository {

    private final JdbcTemplate jdbcTemplate;

    public MenuInventoryCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean claimOrValidate(
            String commandId,
            InventoryLedgerOperation operationType,
            String canonicalInput,
            String sourceCommandId
    ) {
        String fingerprint = RequestFingerprint.of(canonicalInput);
        int inserted = jdbcTemplate.update("""
                insert ignore into menu_inventory_commands
                    (command_id, operation_type, request_fingerprint, source_command_id,
                     created_at, updated_at)
                values (?, ?, ?, ?, current_timestamp(6), current_timestamp(6))
                """, commandId, operationType.name(), fingerprint, sourceCommandId);
        if (inserted == 1) {
            return true;
        }

        StoredCommand stored = jdbcTemplate.queryForObject("""
                select operation_type, request_fingerprint, source_command_id
                from menu_inventory_commands
                where command_id = ?
                for update
                """, (rs, rowNum) -> new StoredCommand(
                        InventoryLedgerOperation.valueOf(rs.getString("operation_type")),
                        rs.getString("request_fingerprint"),
                        rs.getString("source_command_id")), commandId);
        if (stored == null
                || stored.operationType() != operationType
                || !stored.requestFingerprint().equals(fingerprint)
                || !Objects.equals(stored.sourceCommandId(), sourceCommandId)) {
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return false;
    }

    private record StoredCommand(
            InventoryLedgerOperation operationType,
            String requestFingerprint,
            String sourceCommandId
    ) {
    }
}
