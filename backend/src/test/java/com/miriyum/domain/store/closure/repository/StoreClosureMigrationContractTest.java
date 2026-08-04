package com.miriyum.domain.store.closure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StoreClosureMigrationContractTest {

    @Test
    void migrationProtectsScheduledEffectiveAtAndAuditMetadata() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V18__create_store_closures.sql"));

        assertThat(sql).contains(
                "CONSTRAINT uk_regular_closure_store_effective_at",
                "UNIQUE (store_id, effective_at)",
                "actor_type VARCHAR(20) NOT NULL",
                "CHECK (actor_type IN ('STORE_OPERATOR', 'SYSTEM'))",
                "previous_active_version BIGINT NULL",
                "new_active_version BIGINT NULL",
                "requested_at DATETIME(6) NOT NULL",
                "outcome VARCHAR(20) NOT NULL",
                "CHECK (outcome IN ('SUCCEEDED', 'FAILED'))",
                "closure_start_at DATETIME(6) NULL",
                "previous_closure_end_at DATETIME(6) NULL",
                "new_closure_end_at DATETIME(6) NULL",
                "temporary_closure_reason VARCHAR(30) NULL",
                "CONSTRAINT ck_closure_audit_resource_type",
                "CHECK (resource_type IN ('REGULAR', 'TEMPORARY'))",
                "CONSTRAINT ck_closure_audit_temporary_reason",
                "CONSTRAINT ck_closure_audit_temporary_shape",
                "resource_type = 'TEMPORARY'",
                "previous_active_version IS NULL",
                "new_active_version IS NULL",
                "resource_type = 'REGULAR'",
                "closure_start_at IS NULL",
                "previous_closure_end_at IS NULL",
                "new_closure_end_at IS NULL",
                "temporary_closure_reason IS NULL",
                "conflict_check_status VARCHAR(20) NOT NULL",
                "conflict_count INT NULL",
                "CHECK (conflict_check_status IN ('NOT_EVALUATED', 'EVALUATED'))",
                "conflict_check_status = 'NOT_EVALUATED'",
                "conflict_count IS NULL",
                "conflict_check_status = 'EVALUATED'",
                "conflict_count >= 0");
    }
}
