package com.miriyum.domain.menuhold;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MenuHoldMigrationContractTest {

    @Test
    void migrationDefinesReservationHoldAndItemIntegrity() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V21__create_menu_holds.sql"));

        assertThat(sql)
                .contains("CREATE TABLE menu_holds")
                .contains("uk_menu_holds_reservation")
                .contains("acquire_operation_id VARCHAR(100) COLLATE utf8mb4_0900_as_cs NOT NULL")
                .contains("uk_menu_holds_acquire_operation")
                .contains("status IN ('CONFIRMED', 'RELEASED', 'FULFILLED')")
                .contains("FOREIGN KEY (reservation_id) REFERENCES reservations")
                .contains("FOREIGN KEY (store_id) REFERENCES stores")
                .contains("FOREIGN KEY (consumer_account_id) REFERENCES consumer_accounts")
                .contains("CREATE TABLE menu_hold_items")
                .contains("menu_name_snapshot VARCHAR(100) NOT NULL")
                .contains("unit_price_snapshot INT NOT NULL")
                .contains("ck_menu_hold_items_name_snapshot")
                .contains("ck_menu_hold_items_unit_price_snapshot")
                .contains("uk_menu_hold_items_hold_bucket UNIQUE (menu_hold_id, menu_inventory_bucket_id)")
                .doesNotContain("uk_menu_hold_items_hold_menu")
                .contains("FOREIGN KEY (menu_hold_id) REFERENCES menu_holds")
                .contains("FOREIGN KEY (menu_id) REFERENCES menus")
                .contains("FOREIGN KEY (menu_inventory_bucket_id) REFERENCES menu_inventory_buckets")
                .contains("idx_menu_hold_items_bucket")
                .contains("ON DELETE RESTRICT");
    }
}
