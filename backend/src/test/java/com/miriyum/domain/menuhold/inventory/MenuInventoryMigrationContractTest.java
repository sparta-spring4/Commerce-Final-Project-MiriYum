package com.miriyum.domain.menuhold.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MenuInventoryMigrationContractTest {

    @Test
    void migrationDefinesBucketPoolLedgerAndIdempotencyConstraints() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V18__create_menu_inventory_runtime.sql"));

        assertThat(sql)
                .contains("CREATE TABLE menu_inventory_buckets")
                .contains("uk_menu_inventory_bucket_key")
                .contains("ck_menu_inventory_pool_allocation")
                .contains("CREATE TABLE menu_inventory_commands")
                .contains("request_fingerprint CHAR(64)")
                .contains("CREATE TABLE menu_inventory_ledger")
                .contains("uk_menu_inventory_ledger_command_pool")
                .contains("source_command_id VARCHAR(100)")
                .contains("uk_menu_inventory_restore_source_pool")
                .contains("ON DELETE RESTRICT");
    }
}
