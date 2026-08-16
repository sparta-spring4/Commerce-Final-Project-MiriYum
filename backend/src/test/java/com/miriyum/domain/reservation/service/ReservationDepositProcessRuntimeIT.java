package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.reservation.entity.ReservationDepositCalculationSnapshot;
import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.Calculation;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.ItemSnapshot;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.task.scheduling.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class ReservationDepositProcessRuntimeIT {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-20T09:10:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationDepositProcessRepository processRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void persistsProcessAndImmutableCalculationItemsUsingFlywaySchema() {
        seedHoldOwnerAndStore();
        Calculation calculation = new Calculation(
                91L,
                20,
                1L,
                2,
                4_000L,
                "KRW",
                13L,
                40_000L,
                2,
                List.of(
                        new ItemSnapshot("101", 4, 18_000),
                        new ItemSnapshot("102", 8, 22_000)));
        PaymentPreparation payment = new PaymentPreparation(
                "900000000000000077",
                "deposit-runtime-portone",
                "미리윰 식당 예약금",
                4_000L,
                "KRW",
                EXPIRES_AT,
                PaymentStatus.READY);

        ReservationDepositProcess saved = processRepository.saveAndFlush(
                ReservationDepositProcess.awaitingPayment(
                        40_077L,
                        10_077L,
                        EXPIRES_AT,
                        calculation,
                        payment,
                        REQUESTED_AT));
        long processId = saved.getId();
        entityManager.clear();

        ReservationDepositProcess found = processRepository.findById(processId).orElseThrow();

        assertThat(found.getStatus())
                .isEqualTo(ReservationDepositProcessStatus.AWAITING_PAYMENT);
        assertThat(found.getReconciliationNextAttemptAt()).isEqualTo(REQUESTED_AT);
        assertThat(found.isResourcesProtected()).isFalse();
        assertThat(found.getCalculationSnapshot().getCurrency()).isEqualTo("KRW");
        assertThat(found.getCalculationSnapshot().getItems())
                .extracting(ReservationDepositCalculationSnapshot.Item::getMenuId)
                .containsExactlyInAnyOrder("101", "102");
    }

    private void seedHoldOwnerAndStore() {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name,
                    status, created_at, updated_at
                ) VALUES (
                    10077, 'deposit-runtime@example.com', 'hash', '예약금 회원',
                    'ACTIVE', NOW(6), NOW(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name,
                    status, created_at, updated_at
                ) VALUES (
                    20077, 'deposit-runtime-owner@example.com', 'hash', '예약금 운영자',
                    'ACTIVE', NOW(6), NOW(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    business_type, name, description, region, address, time_zone_id,
                    applicant_self_attested_at, required_terms_agreed_at,
                    required_terms_version, store_category_code, verification_status,
                    operation_status, reservation_enabled, menu_hold_enabled,
                    pickup_enabled, created_at, updated_at
                ) VALUES (
                    30077, 20077, '9876543277', 'CAFE', '예약금 런타임 매장', '',
                    'SEOUL', '서울시 중구', 'Asia/Seoul', NOW(6), NOW(6),
                    'STORE_ONBOARDING_REQUIRED_TERMS_V1', 'CAFE_BAKERY',
                    'APPROVED', 'OPEN', TRUE, TRUE, TRUE, NOW(6), NOW(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO reservation_holds (
                    reservation_hold_id, consumer_account_id, store_id,
                    store_name_snapshot, service_date, start_at, service_end_at,
                    occupancy_end_at, time_zone_id_snapshot, start_offset_seconds,
                    service_end_offset_seconds, occupancy_end_offset_seconds,
                    slot_interval_minutes, service_duration_minutes,
                    turnover_duration_minutes, reservation_time_policy_store_id,
                    reservation_policy_version, adult_count, child_count, infant_count,
                    notification_target_reference, contact_available_at_confirmation,
                    capacity_policy_version, cancellation_policy_version, status,
                    status_version, creation_command_id, created_at, expires_at
                ) VALUES (
                    40077, 10077, 30077, '예약금 런타임 매장', '2026-08-21',
                    '2026-08-21 09:00:00.000000', '2026-08-21 10:00:00.000000',
                    '2026-08-21 10:10:00.000000', 'Asia/Seoul', 32400, 32400,
                    32400, 10, 60, 10, 30077, 9, 2, 0, 0,
                    'consumer:10077:channel:primary', TRUE, 7, 8, 'ACTIVE', 0,
                    'deposit-runtime-hold-77',
                    '2026-08-20 09:00:00.000000',
                    '2026-08-20 09:10:00.000000'
                )
                """);
    }
}
