package com.miriyum.domain.store.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
class StoreOnboardingEvidenceMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0.40");

    @Test
    @DisplayName("V10은 기존 매장에 입점 동의 증거를 backfill하고 필수화한다")
    void migratesExistingStoreOnboardingEvidence() throws Exception {
        Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("9"))
                .load()
                .migrate();
        insertLegacyStore();

        Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword())
                .load()
                .migrate();

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet evidence = statement.executeQuery("""
                     SELECT applicant_self_attested_at,
                            required_terms_agreed_at,
                            required_terms_version
                     FROM stores
                     WHERE business_registration_number = '1234567890'
                     """)) {
            assertThat(evidence.next()).isTrue();
            assertThat(evidence.getTimestamp("applicant_self_attested_at")
                    .toLocalDateTime())
                    .isEqualTo(LocalDateTime.of(2026, 7, 31, 12, 0));
            assertThat(evidence.getTimestamp("required_terms_agreed_at")
                    .toLocalDateTime())
                    .isEqualTo(LocalDateTime.of(2026, 7, 31, 12, 0));
            assertThat(evidence.getString("required_terms_version"))
                    .isEqualTo("STORE_ONBOARDING_REQUIRED_TERMS_V1");
        }

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("""
                     SELECT column_name, is_nullable
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = 'stores'
                       AND column_name IN (
                           'applicant_self_attested_at',
                           'required_terms_agreed_at',
                           'required_terms_version'
                       )
                     ORDER BY column_name
                     """)) {
            int count = 0;
            while (columns.next()) {
                count++;
                assertThat(columns.getString("is_nullable")).isEqualTo("NO");
            }
            assertThat(count).isEqualTo(3);
        }

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet removed = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = 'stores'
                       AND column_name = 'pickup_eligibility'
                     """)) {
            assertThat(removed.next()).isTrue();
            assertThat(removed.getInt(1)).isZero();
        }
    }

    private void insertLegacyStore() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO store_operator_accounts (
                        email, password_hash, display_name
                    ) VALUES (
                        'legacy@example.com', 'hashed', '기존 운영자'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO stores (
                        store_operator_account_id,
                        business_registration_number,
                        business_type,
                        name,
                        description,
                        region,
                        address,
                        store_category_code,
                        verification_status,
                        operation_status,
                        pickup_eligibility,
                        reservation_enabled,
                        menu_hold_enabled,
                        pickup_enabled,
                        created_at,
                        updated_at
                    ) VALUES (
                        1,
                        '1234567890',
                        'CAFE',
                        '기존 매장',
                        '',
                        'SEOUL',
                        '서울시 중구',
                        'CAFE_BAKERY',
                        'APPROVED',
                        'OPEN',
                        'ELIGIBLE',
                        TRUE,
                        TRUE,
                        TRUE,
                        '2026-07-31 12:00:00.000000',
                        '2026-07-31 12:00:00.000000'
                    )
                    """);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword());
    }
}
