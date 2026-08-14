package com.miriyum.domain.store.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.entity.StoreReservationDepositPolicy;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.task.scheduling.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class StoreReservationDepositPolicyMigrationTest {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private StoreReservationDepositPolicyRepository policyRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM store_reservation_deposit_policies");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        entityManager.clear();
    }

    @Test
    @Transactional
    @DisplayName("V40 스키마와 JPA Repository로 현재 예약금 정책을 저장하고 조회한다")
    void storesAndReadsCurrentPolicyWithFlywaySchema() {
        long storeId = createStore();

        policyRepository.saveAndFlush(
                StoreReservationDepositPolicy.create(storeId, false, 10));
        entityManager.clear();

        StoreReservationDepositPolicy found =
                policyRepository.findById(storeId).orElseThrow();
        assertThat(found.isEnabled()).isFalse();
        assertThat(found.getRatePercent()).isEqualTo(10);
        assertThat(found.getPolicyVersion()).isEqualTo(1L);
        assertThat(found.getLockVersion()).isZero();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 잠금 버전을 읽은 두 정책 변경 중 stale write는 거부한다")
    void rejectsStalePolicyUpdateWithJpaVersion() {
        long storeId = createStore();
        policyRepository.saveAndFlush(
                StoreReservationDepositPolicy.create(storeId, false, 20));

        StoreReservationDepositPolicy first = transactionTemplate.execute(ignored ->
                policyRepository.findById(storeId).orElseThrow());
        StoreReservationDepositPolicy stale = transactionTemplate.execute(ignored ->
                policyRepository.findById(storeId).orElseThrow());
        first.update(true, 20);
        stale.update(false, 25);

        transactionTemplate.executeWithoutResult(ignored ->
                policyRepository.saveAndFlush(first));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored ->
                policyRepository.saveAndFlush(stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("DB는 1:1 PK/FK와 비율·업무 버전·잠금 버전 제약을 강제한다")
    void enforcesPolicyDatabaseConstraints() {
        long storeId = createStore();

        Integer primaryKeyCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.table_constraints
                        WHERE table_schema = DATABASE()
                          AND table_name = 'store_reservation_deposit_policies'
                          AND constraint_type = 'PRIMARY KEY'
                        """,
                Integer.class);
        Integer uniqueConstraintCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.table_constraints
                        WHERE table_schema = DATABASE()
                          AND table_name = 'store_reservation_deposit_policies'
                          AND constraint_type = 'UNIQUE'
                        """,
                Integer.class);
        assertThat(primaryKeyCount).isOne();
        assertThat(uniqueConstraintCount).isZero();

        assertInvalidPolicyRow(storeId, 9, 1L, 0L);
        assertInvalidPolicyRow(storeId, 31, 1L, 0L);
        assertInvalidPolicyRow(storeId, 20, 0L, 0L);
        assertInvalidPolicyRow(storeId, 20, 1L, -1L);
        assertInvalidPolicyRow(storeId + 999L, 20, 1L, 0L);

        insertPolicyRow(storeId, 20, 1L, 0L);
        assertInvalidPolicyRow(storeId, 25, 2L, 0L);
    }

    @Test
    @DisplayName("V40은 기존 매장에 정책 행을 backfill하지 않는다")
    void migratesExistingSchemaWithoutPolicyBackfill() throws Exception {
        try (MySQLContainer legacy = new MySQLContainer(
                DockerImageName.parse("mysql:8.0.40"))) {
            legacy.start();
            Flyway.configure()
                    .dataSource(
                            legacy.getJdbcUrl(),
                            legacy.getUsername(),
                            legacy.getPassword())
                    .target(MigrationVersion.fromVersion("39"))
                    .load()
                    .migrate();
            insertLegacyStore(legacy);

            Flyway flyway = Flyway.configure()
                    .dataSource(
                            legacy.getJdbcUrl(),
                            legacy.getUsername(),
                            legacy.getPassword())
                    .load();
            flyway.migrate();

            assertThat(flyway.info().applied())
                    .extracting(MigrationInfo::getScript)
                    .contains("V40__create_store_reservation_deposit_policies.sql");
            try (var connection = legacy.createConnection("");
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT COUNT(*) FROM store_reservation_deposit_policies")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private long createStore() {
        long operatorId = storeOperatorAccountRepository.saveAndFlush(
                StoreOperatorAccount.create(
                        "deposit-policy-owner@example.com", "hashed", "운영자"))
                .getId();
        Store store = Store.create(
                operatorId,
                "1234567890",
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of(),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 7, 31, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1");
        return storeRepository.saveAndFlush(store).getId();
    }

    private void assertInvalidPolicyRow(
            long storeId,
            int ratePercent,
            long policyVersion,
            long lockVersion
    ) {
        assertThatThrownBy(() ->
                insertPolicyRow(storeId, ratePercent, policyVersion, lockVersion))
                .isInstanceOf(DataAccessException.class);
    }

    private void insertPolicyRow(
            long storeId,
            int ratePercent,
            long policyVersion,
            long lockVersion
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO store_reservation_deposit_policies (
                            store_id, enabled, rate_percent, policy_version,
                            lock_version, created_at, updated_at
                        ) VALUES (?, FALSE, ?, ?, ?, NOW(6), NOW(6))
                        """,
                storeId,
                ratePercent,
                policyVersion,
                lockVersion);
    }

    private void insertLegacyStore(MySQLContainer legacy) throws Exception {
        try (var connection = legacy.createConnection("");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO store_operator_accounts (
                        email, password_hash, display_name
                    ) VALUES (
                        'legacy-deposit-owner@example.com', 'hashed', '기존 운영자'
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
                        '9876543210',
                        'CAFE',
                        '기존 매장',
                        '',
                        'SEOUL',
                        '서울시 중구',
                        'Asia/Seoul',
                        'CAFE_BAKERY',
                        'APPROVED',
                        'OPEN',
                        TRUE,
                        TRUE,
                        TRUE,
                        '2026-08-14 00:00:00.000000',
                        '2026-08-14 00:00:00.000000',
                        'STORE_ONBOARDING_REQUIRED_TERMS_V1',
                        '2026-08-14 00:00:00.000000',
                        '2026-08-14 00:00:00.000000'
                    )
                    """);
        }
    }
}
