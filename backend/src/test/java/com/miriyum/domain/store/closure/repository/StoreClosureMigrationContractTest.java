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
                "conflict_check_status VARCHAR(20) NOT NULL",
                "conflict_count INT NULL",
                "CHECK (conflict_check_status IN ('NOT_EVALUATED', 'EVALUATED'))",
                "conflict_check_status = 'NOT_EVALUATED'",
                "conflict_count IS NULL",
                "conflict_check_status = 'EVALUATED'",
                "conflict_count >= 0");
    }
}
