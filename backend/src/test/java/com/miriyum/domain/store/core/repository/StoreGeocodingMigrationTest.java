package com.miriyum.domain.store.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class StoreGeocodingMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @Test
    @DisplayName("V20은 레거시 매장을 주소 버전 1의 좌표 없는 미검증 상태로 이관한다")
    void migratesLegacyStoreToUnverifiedAddressVersionOne() throws Exception {
        cleanDatabase();
        migrateToVersion19();
        insertLegacyStore();

        migrateToLatest();

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT address_version,
                            geocoding_status,
                            latitude,
                            longitude,
                            verified_address,
                            geocoding_verified_at,
                            geocoding_address_version,
                            geocoding_provider,
                            geocoding_provider_api_version
                     FROM stores
                     WHERE business_registration_number = '1234567890'
                     """)) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong("address_version")).isEqualTo(1L);
            assertThat(row.getString("geocoding_status")).isEqualTo("UNVERIFIED");
            assertThat(row.getBigDecimal("latitude")).isNull();
            assertThat(row.getBigDecimal("longitude")).isNull();
            assertThat(row.getString("verified_address")).isNull();
            assertThat(row.getTimestamp("geocoding_verified_at")).isNull();
            assertThat(row.getObject("geocoding_address_version")).isNull();
            assertThat(row.getString("geocoding_provider")).isNull();
            assertThat(row.getString("geocoding_provider_api_version")).isNull();
        }
    }

    @Test
    @DisplayName("V20은 불완전하거나 현재 주소 버전과 불일치한 검증 좌표를 거부한다")
    void rejectsInvalidVerifiedCoordinateShapes() throws Exception {
        cleanDatabase();
        migrateToVersion19();
        insertLegacyStore();
        migrateToLatest();

        assertSqlRejected("""
                UPDATE stores
                SET geocoding_status = 'VERIFIED'
                WHERE business_registration_number = '1234567890'
                """);
        assertSqlRejected(verifiedUpdate("2", "37.566826000000000", "126.978656700000000"));
        assertSqlRejected(verifiedUpdate("1", "91.000000000000000", "126.978656700000000"));
        assertSqlRejected(verifiedUpdate("1", "37.566826000000000", "181.000000000000000"));
    }

    private void migrateToVersion19() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("19"))
                .load()
                .migrate();
    }

    private void cleanDatabase() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    private void migrateToLatest() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    private void insertLegacyStore() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO store_operator_accounts (
                        email, password_hash, display_name
                    ) VALUES (
                        'geocoding-legacy@example.com', 'hashed', '기존 운영자'
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
                        time_zone_id,
                        store_category_code,
                        verification_status,
                        operation_status,
                        pickup_eligibility,
                        reservation_enabled,
                        menu_hold_enabled,
                        pickup_enabled,
                        applicant_self_attested_at,
                        required_terms_agreed_at,
                        required_terms_version,
                        created_at,
                        updated_at
                    ) VALUES (
                        1,
                        '1234567890',
                        'CAFE',
                        '기존 매장',
                        '',
                        'SEOUL',
                        '서울 중구 세종대로 110',
                        'Asia/Seoul',
                        'CAFE_BAKERY',
                        'APPROVED',
                        'OPEN',
                        'ELIGIBLE',
                        TRUE,
                        TRUE,
                        TRUE,
                        '2026-07-31 12:00:00.000000',
                        '2026-07-31 12:00:00.000000',
                        'STORE_ONBOARDING_REQUIRED_TERMS_V1',
                        '2026-07-31 12:00:00.000000',
                        '2026-07-31 12:00:00.000000'
                    )
                    """);
        }
    }

    private void assertSqlRejected(String sql) {
        assertThatThrownBy(() -> {
            try (Connection connection = connection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }).isInstanceOfSatisfying(SQLException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(3819);
            assertThat(failure.getMessage()).contains("ck_stores_geocoding_shape");
        });
    }

    private String verifiedUpdate(
            String geocodingAddressVersion,
            String latitude,
            String longitude
    ) {
        return """
                UPDATE stores
                SET geocoding_status = 'VERIFIED',
                    latitude = %s,
                    longitude = %s,
                    verified_address = '서울 중구 세종대로 110',
                    geocoding_verified_at = '2026-08-04 09:00:00.000000',
                    geocoding_address_version = %s,
                    geocoding_provider = 'KAKAO_LOCAL',
                    geocoding_provider_api_version = 'v2'
                WHERE business_registration_number = '1234567890'
                """.formatted(latitude, longitude, geocodingAddressVersion);
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword());
    }
}
