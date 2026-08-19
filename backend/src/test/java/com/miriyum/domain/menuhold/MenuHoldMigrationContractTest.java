package com.miriyum.domain.menuhold;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MenuHoldMigrationContractTest {

    private static final Path V38 = Path.of(
            "src/main/resources/db/migration/V38__add_temporary_menu_holds.sql");
    private static final Path V48 = Path.of(
            "src/main/resources/db/migration/V48__add_menu_hold_forfeited_status.sql");
    private static final Path V64 = Path.of(
            "src/main/resources/db/migration/V64__add_menu_hold_monitoring_ledger.sql");

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

    @Test
    void v38AddsNullableLegacyAndTemporaryParentColumns() throws IOException {
        String sql = normalizedV38();

        assertThat(sql)
                .as("menu_holds schema replacement must stay atomic in MySQL")
                .containsOnlyOnce("ALTER TABLE menu_holds")
                .as("legacy MenuHold creation would fail if reservation_id stayed NOT NULL")
                .contains("ALTER TABLE menu_holds "
                        + "DROP CHECK ck_menu_holds_status, "
                        + "MODIFY reservation_id BIGINT NULL, "
                        + "MODIFY status VARCHAR(32) NOT NULL, "
                        + "ADD reservation_hold_id BIGINT NULL, "
                        + "ADD expires_at DATETIME(6) NULL,");
        assertThat(sql)
                .as("temporary MenuHold rows would lose their ReservationHold parent")
                .contains("ADD reservation_hold_id BIGINT NULL");
        assertThat(sql)
                .as("temporary MenuHold rows would not preserve the parent's expiry")
                .contains("ADD expires_at DATETIME(6) NULL");
        assertThat(sql)
                .as("RECONCILIATION_REQUIRED would be truncated by the legacy status width")
                .contains("MODIFY status VARCHAR(32) NOT NULL");
    }

    @Test
    void v38DefinesOneTemporaryMenuHoldPerReservationHold() throws IOException {
        String sql = normalizedV38();

        assertThat(sql)
                .as("the composite FK target would not be a declared unique key")
                .contains("ALTER TABLE reservation_holds ADD CONSTRAINT "
                        + "uk_reservation_holds_id_expires UNIQUE "
                        + "(reservation_hold_id, expires_at);");
        assertThat(sql)
                .as("one ReservationHold could own more than one temporary MenuHold root")
                .contains("CONSTRAINT uk_menu_holds_reservation_hold "
                        + "UNIQUE (reservation_hold_id)");
        assertThat(sql)
                .as("a MenuHold could link to a ReservationHold with a different expiry")
                .contains("CONSTRAINT fk_menu_holds_reservation_hold_expiration "
                        + "FOREIGN KEY (reservation_hold_id, expires_at) "
                        + "REFERENCES reservation_holds (reservation_hold_id, expires_at) "
                        + "ON DELETE RESTRICT");
    }

    @Test
    void v38ReplacesLegacyStatusCheckWithParentAndStateInvariant() throws IOException {
        String sql = normalizedV38();

        assertThat(sql)
                .as("the legacy status-only CHECK would reject every temporary state")
                .contains("ALTER TABLE menu_holds DROP CHECK ck_menu_holds_status,");
        assertThat(sql)
                .as("legacy and temporary parent/state/nullability combinations could drift")
                .contains("CONSTRAINT ck_menu_holds_parent_and_status CHECK ( "
                        + "(reservation_hold_id IS NULL AND expires_at IS NULL "
                        + "AND reservation_id IS NOT NULL "
                        + "AND status IN ('CONFIRMED', 'RELEASED', 'FULFILLED')) OR "
                        + "(reservation_hold_id IS NOT NULL AND expires_at IS NOT NULL "
                        + "AND reservation_id IS NULL "
                        + "AND status IN ('ACTIVE', 'RECONCILIATION_REQUIRED', "
                        + "'RELEASED', 'EXPIRED')) OR "
                        + "(reservation_hold_id IS NOT NULL AND expires_at IS NOT NULL "
                        + "AND reservation_id IS NOT NULL "
                        + "AND status IN ('CONFIRMED', 'RELEASED', 'FULFILLED')) )");
    }

    @Test
    void v48AllowsForfeitedOnlyForFinalReservationLinkedHolds() throws IOException {
        String sql = Files.readString(V48).replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .containsOnlyOnce("ALTER TABLE menu_holds")
                .contains("DROP CHECK ck_menu_holds_parent_and_status")
                .contains("CONSTRAINT ck_menu_holds_parent_and_status CHECK ( "
                        + "(reservation_hold_id IS NULL AND expires_at IS NULL "
                        + "AND reservation_id IS NOT NULL AND status IN "
                        + "('CONFIRMED', 'RELEASED', 'FULFILLED', 'FORFEITED')) OR "
                        + "(reservation_hold_id IS NOT NULL AND expires_at IS NOT NULL "
                        + "AND reservation_id IS NULL AND status IN "
                        + "('ACTIVE', 'RECONCILIATION_REQUIRED', 'RELEASED', 'EXPIRED')) OR "
                        + "(reservation_hold_id IS NOT NULL AND expires_at IS NOT NULL "
                        + "AND reservation_id IS NOT NULL AND status IN "
                        + "('CONFIRMED', 'RELEASED', 'FULFILLED', 'FORFEITED')) )")
                .doesNotContain("reservation_id IS NULL AND status IN "
                        + "('ACTIVE', 'RECONCILIATION_REQUIRED', 'RELEASED', 'EXPIRED', "
                        + "'FORFEITED')");
    }

    @Test
    void v64AddsVersionedAppendOnlyMonitoringLedgerWithTruthfulBaseline() throws IOException {
        String sql = Files.readString(V64).replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("ADD status_version BIGINT NOT NULL DEFAULT 0")
                .contains("CONSTRAINT ck_menu_holds_status_version CHECK (status_version >= 0)")
                .contains("CREATE TABLE menu_hold_transition_audits")
                .contains("event_type VARCHAR(16) NOT NULL")
                .contains("before_status VARCHAR(32) NULL")
                .contains("after_status VARCHAR(32) NOT NULL")
                .contains("result_version BIGINT NOT NULL")
                .contains("occurred_at DATETIME(6) NOT NULL")
                .contains("CONSTRAINT uk_menu_hold_transition_version UNIQUE (menu_hold_id, result_version)")
                .contains("CREATE INDEX idx_menu_hold_transition_occurred")
                .contains("SELECT menu_hold_id, reservation_id, reservation_hold_id, 'BASELINE', NULL, status, 0, UTC_TIMESTAMP(6)")
                .contains("BEFORE UPDATE ON menu_hold_transition_audits")
                .contains("BEFORE DELETE ON menu_hold_transition_audits")
                .contains("SIGNAL SQLSTATE '45000'")
                .contains("menu hold transition audits are immutable")
                .doesNotContain("'TRANSITION', NULL, status, 0")
                .doesNotContain("created_at AS occurred_at")
                .doesNotContain("updated_at AS occurred_at");
    }

    private static String normalizedV38() throws IOException {
        return Files.readString(V38).replaceAll("\\s+", " ").trim();
    }
}
