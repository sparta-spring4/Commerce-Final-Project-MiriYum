package com.miriyum.domain.menuhold.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MenuInventoryMigrationContractTest {

    @Test
    void migrationDefinesBucketPoolAndOperationLedgerConstraints() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V18__create_menu_inventory_runtime.sql"));

        assertThat(sql)
                .contains("CREATE TABLE menu_inventory_buckets")
                .contains("uk_menu_inventory_bucket_key")
                .contains("ck_menu_inventory_pool_allocation")
                .doesNotContain("CREATE TABLE menu_inventory_commands")
                .contains("CREATE TABLE menu_inventory_ledger")
                .contains("uk_menu_inventory_ledger_operation_pool")
                .contains("operation_id VARCHAR(100)")
                .contains("source_operation_id VARCHAR(100)")
                .contains("uk_menu_inventory_restore_source_pool")
                .contains("ON DELETE RESTRICT");
    }
}
