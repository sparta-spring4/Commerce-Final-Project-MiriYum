package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.menu.service.RepresentativeMenuQueryService;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.dto.request.ReservationCreateRequest;
import com.miriyum.domain.reservation.dto.request.ReservationPartyRequest;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.schedule.closure.service.RegularClosureActivationJob;
import com.miriyum.domain.schedule.dto.contract.StoreReservationWindowResult;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.service.StoreScheduleActivationJob;
import com.miriyum.domain.schedule.service.StoreScheduleService;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.domain.store.service.StoreReservationDepositPolicyQueryService;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
            "miriyum.reservation.deposit-worker.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.payment.cursor-secret=test-history-cursor-secret-with-enough-entropy",
            "miriyum.payment.portone.api-secret=test-api-secret",
            "miriyum.payment.portone.webhook-secret=whsec_dGVzdC1zZWNyZXQ=",
            "miriyum.payment.portone.store-id=store-1"
        })
@Import(ReservationDepositCreationRuntimeIT.MutableClockConfig.class)
class ReservationDepositCreationRuntimeIT {

    private static final long CONSUMER_ID = 10_088L;
    private static final long OPERATOR_ID = 20_088L;
    private static final long STORE_ID = 30_088L;
    private static final long CAPACITY_BUCKET_ID = 50_088L;
    private static final long MENU_ID = 60_088L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 21);
    private static final LocalTime START_TIME = LocalTime.of(9, 0);
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T09:00:00Z");
    private static final IdempotencyKey CREATION_KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440088");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationCreationCommandFacade creationFacade;

    @Autowired
    private MutableClock clock;

    @MockitoSpyBean
    private StoreTransactionEligibilityService storeEligibilityService;

    @MockitoSpyBean
    private StoreReservationDepositPolicyQueryService depositPolicyQueryService;

    @MockitoSpyBean
    private RepresentativeMenuQueryService representativeMenuQueryService;

    @MockitoSpyBean
    private PaymentService paymentService;

    @MockitoBean
    private StoreScheduleService storeScheduleService;

    @MockitoBean
    private StoreServiceIntervalValidationService intervalValidationService;

    @MockitoBean
    private PaymentProviderClient paymentProviderClient;

    @MockitoBean
    private StoreScheduleActivationJob storeScheduleActivationJob;

    @MockitoBean
    private RegularClosureActivationJob regularClosureActivationJob;

    private final List<TransactionObservation> observations = new ArrayList<>();

    @BeforeEach
    void resetDatabase() {
        clock.set(REQUESTED_AT);
        observations.clear();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_deposit_process_creation_failure");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_refund_obligations");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_cause_audits");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_calculation_items");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_processes");
        jdbcTemplate.execute("DELETE FROM reservation_hold_warning_tasks");
        jdbcTemplate.execute("DELETE FROM reservation_hold_transition_audits");
        jdbcTemplate.execute("DELETE FROM reservation_hold_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservation_holds");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_buckets");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM payment_webhook_receipts");
        jdbcTemplate.execute("DELETE FROM payment_ledger_entries");
        jdbcTemplate.execute("TRUNCATE TABLE payment_refund_monitoring_snapshots");
        jdbcTemplate.execute("TRUNCATE TABLE payment_monitoring_snapshots");
        jdbcTemplate.execute("DELETE FROM payment_refunds");
        jdbcTemplate.execute("DELETE FROM payment_attempts");
        jdbcTemplate.execute("DELETE FROM payments");
        jdbcTemplate.execute("DELETE FROM payment_public_ids");
        jdbcTemplate.execute("DELETE FROM refund_public_ids");
        jdbcTemplate.execute("DELETE FROM representative_menu_audits");
        jdbcTemplate.execute("DELETE FROM representative_menu_entries");
        jdbcTemplate.execute("DELETE FROM representative_menu_settings");
        jdbcTemplate.execute("DELETE FROM menu_versions");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_audits");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_versions");
        jdbcTemplate.execute("DELETE FROM store_reservation_deposit_policies");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");

        when(storeScheduleService.resolveReservationWindows(
                List.of(STORE_ID), SERVICE_DATE, START_TIME)).thenReturn(List.of(
                        StoreReservationWindowResult.accepting(
                                STORE_ID,
                                "UTC",
                                LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 0)),
                                LocalDateTime.of(SERVICE_DATE, LocalTime.of(18, 0)))));
        StoreServiceIntervalRequest intervalRequest = new StoreServiceIntervalRequest(
                STORE_ID,
                Instant.parse("2026-08-21T09:00:00Z"),
                Instant.parse("2026-08-21T10:00:00Z"));
        when(intervalValidationService.validateServiceIntervals(List.of(intervalRequest)))
                .thenReturn(List.of(StoreServiceIntervalResult.of(intervalRequest, true)));

        StoreTransactionEligibilityService storeEligibilityTarget =
                AopTestUtils.getUltimateTargetObject(storeEligibilityService);
        StoreReservationDepositPolicyQueryService depositPolicyQueryTarget =
                AopTestUtils.getUltimateTargetObject(depositPolicyQueryService);
        RepresentativeMenuQueryService representativeMenuQueryTarget =
                AopTestUtils.getUltimateTargetObject(representativeMenuQueryService);
        PaymentService paymentTarget = AopTestUtils.getUltimateTargetObject(paymentService);
        doAnswer(invocation -> observeRealCall("store", invocation))
                .when(storeEligibilityTarget)
                .requireReservationTransactionEligibility(STORE_ID);
        doAnswer(invocation -> observeRealCall("policy", invocation))
                .when(depositPolicyQueryTarget)
                .getCurrent(STORE_ID);
        doAnswer(invocation -> observeRealCall("menu", invocation))
                .when(representativeMenuQueryTarget)
                .getCurrent(STORE_ID);
        doAnswer(invocation -> observeRealCall("payment", invocation))
                .when(paymentTarget)
                .prepareReservationDeposit(
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("예약금 202 생성은 Store→정책→대표 메뉴→Payment를 같은 transaction에 원자 저장한다")
    void createsAcceptedDepositRequestInOneCallerTransaction() {
        seedCreationInputs();

        ReservationCreationCommandResult result = creationFacade.create(
                CONSUMER_ID,
                CREATION_KEY,
                request());

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.responseData())
                .isInstanceOfSatisfying(ReservationRequestResponse.class, response -> {
                    assertThat(response.status())
                            .isEqualTo(ReservationDepositProcessStatus.AWAITING_PAYMENT);
                    assertThat(response.expiresAt().toInstant())
                            .isEqualTo(REQUESTED_AT.plusSeconds(600));
                    assertThat(response.paymentPreparation().amountMinor()).isEqualTo(16_000L);
                    assertThat(response.paymentPreparation().currency()).isEqualTo("KRW");
                    assertThat(response.paymentPreparation().status()).isEqualTo("READY");
                    assertThat(response.abandonmentRequested()).isFalse();
                    assertThat(response.reservation()).isNull();
                });
        assertThat(observations)
                .extracting(TransactionObservation::boundary)
                .containsExactly("store", "policy", "menu", "payment");
        assertThat(observations).allSatisfy(observation -> {
            assertThat(observation.transactionActive()).isTrue();
            assertThat(observation.transactionName())
                    .endsWith("ReservationService.createReservation");
        });
        assertThat(observations)
                .extracting(TransactionObservation::connectionId)
                .containsOnly(observations.getFirst().connectionId());

        long processId = jdbcTemplate.queryForObject(
                "SELECT reservation_deposit_process_id FROM reservation_deposit_processes",
                Long.class);
        long holdId = jdbcTemplate.queryForObject(
                "SELECT reservation_hold_id FROM reservation_deposit_processes",
                Long.class);
        String paymentId = jdbcTemplate.queryForObject(
                "SELECT payment_id FROM reservation_deposit_processes",
                String.class);
        assertThat(((ReservationRequestResponse) result.responseData()).reservationRequestId())
                .isEqualTo(String.valueOf(processId));
        assertThat(((ReservationRequestResponse) result.responseData())
                .paymentPreparation().paymentId()).isEqualTo(paymentId);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, consumer_account_id, payment_amount_minor,
                       payment_currency, store_deposit_policy_version,
                       deposit_rate_percent, deposit_algorithm_version,
                       deposit_party_size, representative_menu_version,
                       representative_menu_price_total, representative_menu_count
                  FROM reservation_deposit_processes
                 WHERE reservation_deposit_process_id = ?
                """, processId))
                .containsEntry("status", "AWAITING_PAYMENT")
                .containsEntry("consumer_account_id", CONSUMER_ID)
                .containsEntry("payment_amount_minor", 16_000L)
                .containsEntry("payment_currency", "KRW")
                .containsEntry("store_deposit_policy_version", 91L)
                .containsEntry("deposit_rate_percent", 20)
                .containsEntry("deposit_algorithm_version", 1L)
                .containsEntry("deposit_party_size", 2)
                .containsEntry("representative_menu_version", 13L)
                .containsEntry("representative_menu_price_total", 40_000L)
                .containsEntry("representative_menu_count", 1);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT menu_id, published_version_number, base_price
                  FROM reservation_deposit_calculation_items
                 WHERE reservation_deposit_process_id = ?
                """, processId))
                .containsEntry("menu_id", String.valueOf(MENU_ID))
                .containsEntry("published_version_number", 4)
                .containsEntry("base_price", 40_000);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, creation_command_id, cancellation_policy_version
                  FROM reservation_holds
                 WHERE reservation_hold_id = ?
                """, holdId))
                .containsEntry("status", "ACTIVE")
                .containsEntry("cancellation_policy_version", 2L)
                .containsEntry("creation_command_id",
                        "reservation-deposit-create:" + CREATION_KEY.value());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT occupied_people, occupied_teams
                  FROM reservation_capacity_buckets
                 WHERE reservation_capacity_bucket_id = ?
                """, CAPACITY_BUCKET_ID))
                .containsEntry("occupied_people", 2)
                .containsEntry("occupied_teams", 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_hold_capacity_allocations",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_hold_transition_audits",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_hold_warning_tasks",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT source_type, source_reference_id, consumer_account_id,
                       amount_minor, currency, status
                  FROM payments
                 WHERE payment_id = ?
                """, paymentId))
                .containsEntry("source_type", "RESERVATION_DEPOSIT")
                .containsEntry("source_reference_id", String.valueOf(holdId))
                .containsEntry("consumer_account_id", CONSUMER_ID)
                .containsEntry("amount_minor", 16_000L)
                .containsEntry("currency", "KRW")
                .containsEntry("status", "READY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_ledger_entries",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands "
                        + "WHERE processing_status = 'SUCCEEDED' "
                        + "AND result_http_status = 202",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations", Integer.class)).isZero();
    }

    @Test
    @DisplayName("process 저장 실패는 Hold·Payment·멱등 효과를 모두 롤백하고 같은 키 재시도를 허용한다")
    void processPersistenceFailureRollsBackEveryCreationEffectAndAllowsRetry() {
        seedCreationInputs();

        try {
            jdbcTemplate.execute("""
                    CREATE TRIGGER trg_deposit_process_creation_failure
                    BEFORE INSERT ON reservation_deposit_processes
                    FOR EACH ROW
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'reservation deposit process creation failure'
                    """);

            assertThatThrownBy(() -> creationFacade.create(
                    CONSUMER_ID,
                    CREATION_KEY,
                    request()))
                    .hasRootCauseInstanceOf(java.sql.SQLException.class)
                    .hasStackTraceContaining("reservation deposit process creation failure");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_deposit_process_creation_failure");
        }

        assertCreationEffectsRolledBack();

        ReservationCreationCommandResult retry = creationFacade.create(
                CONSUMER_ID,
                CREATION_KEY,
                request());

        assertThat(retry.httpStatus()).isEqualTo(202);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_deposit_processes",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_holds",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands "
                        + "WHERE command_type = 'RESERVATION_CREATE' "
                        + "AND processing_status = 'SUCCEEDED'",
                Integer.class)).isOne();
    }

    @Test
    @DisplayName("예약금 생성의 같은 멱등 키 replay는 저장된 202를 반환하고 자원을 추가하지 않는다")
    void sameKeyReplayReturnsStoredAcceptedResultWithoutAdditionalResources() {
        seedCreationInputs();

        ReservationCreationCommandResult created = creationFacade.create(
                CONSUMER_ID,
                CREATION_KEY,
                request());
        ReservationCreationCommandResult replayed = creationFacade.create(
                CONSUMER_ID,
                CREATION_KEY,
                request());

        assertThat(replayed).isEqualTo(created);
        assertThat(observations)
                .extracting(TransactionObservation::boundary)
                .containsExactly("store", "policy", "menu", "payment");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_deposit_processes",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_holds",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_ledger_entries",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_public_ids",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT occupied_people, occupied_teams
                  FROM reservation_capacity_buckets
                 WHERE reservation_capacity_bucket_id = ?
                """, CAPACITY_BUCKET_ID))
                .containsEntry("occupied_people", 2)
                .containsEntry("occupied_teams", 1);
    }

    @Test
    @DisplayName("예약금 정책의 대표 메뉴 미구성은 STORE_010이고 자원을 생성하지 않는다")
    void unconfiguredRepresentativeMenuRejectsBeforeCreatingResources() {
        seedCreationInputs();
        jdbcTemplate.execute("DELETE FROM representative_menu_entries");
        jdbcTemplate.execute("DELETE FROM representative_menu_settings");

        assertThatThrownBy(() -> creationFacade.create(
                CONSUMER_ID,
                CREATION_KEY,
                request()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.MENU_STATE_CONFLICT));

        assertThat(observations)
                .extracting(TransactionObservation::boundary)
                .containsExactly("store", "policy", "menu");
        assertCreationEffectsRolledBack();
    }

    private void assertCreationEffectsRolledBack() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_deposit_processes",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_deposit_calculation_items",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_holds",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_hold_capacity_allocations",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_hold_transition_audits",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_hold_warning_tasks",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_ledger_entries",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_public_ids",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT occupied_people, occupied_teams
                  FROM reservation_capacity_buckets
                 WHERE reservation_capacity_bucket_id = ?
                """, CAPACITY_BUCKET_ID))
                .containsEntry("occupied_people", 0)
                .containsEntry("occupied_teams", 0);
    }

    private Object observeRealCall(String boundary, InvocationOnMock invocation)
            throws Throwable {
        observations.add(new TransactionObservation(
                boundary,
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.getCurrentTransactionName(),
                jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class)));
        return invocation.callRealMethod();
    }

    private void seedCreationInputs() {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, phone, name,
                    reservation_contact_reference, status, created_at, updated_at
                ) VALUES (
                    10088, 'deposit-creation@example.com', 'hash', '01012340088',
                    '예약금 회원', 'consumer:10088:channel:primary', 'ACTIVE',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name,
                    status, created_at, updated_at
                ) VALUES (
                    20088, 'deposit-creation-owner@example.com', 'hash', '예약금 운영자',
                    'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    name, description, region, address, time_zone_id,
                    applicant_self_attested_at, required_terms_agreed_at,
                    required_terms_version, store_category_code, verification_status,
                    operation_status, reservation_enabled, menu_hold_enabled,
                    pickup_enabled, created_at, updated_at
                ) VALUES (
                    30088, 20088, '9876543288', '예약금 생성 매장', '',
                    'SEOUL', '서울시 중구', 'UTC', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                    'STORE_ONBOARDING_REQUIRED_TERMS_V1', 'CAFE_BAKERY',
                    'APPROVED', 'OPEN', TRUE, TRUE, TRUE,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO store_reservation_deposit_policies (
                    store_id, enabled, rate_percent, policy_version, lock_version,
                    created_at, updated_at
                ) VALUES (30088, TRUE, 20, 91, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        jdbcTemplate.update("""
                INSERT INTO menus (
                    menu_id, store_id, next_version_number, published_version_number,
                    visibility, selling_status, retired, lock_version, created_at, updated_at
                ) VALUES (
                    60088, 30088, 5, 4, 'VISIBLE', 'SELLING', FALSE, 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO menu_versions (
                    menu_version_id, menu_id, version_number, status, name,
                    description, price, representative, primary_category_code,
                    hold_selection_allowed, pickup_selection_allowed,
                    allergen_information_status, origin_information_status,
                    alcoholic, created_by_operator_id, created_at, effective_at
                ) VALUES (
                    70088, 60088, 4, 'PUBLISHED', '대표 메뉴', '', 40000, TRUE,
                    'BEVERAGE', TRUE, TRUE, 'NOT_REGISTERED', 'NOT_APPLICABLE',
                    FALSE, 20088, '2026-08-19 09:00:00.000000',
                    '2026-08-19 09:00:00.000000'
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO representative_menu_settings (
                    store_id, version, status, lock_version, created_at, updated_at
                ) VALUES (
                    30088, 13, 'CONFIGURED', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO representative_menu_entries (store_id, display_order, menu_id)
                VALUES (30088, 1, 60088)
                """);
        jdbcTemplate.update("""
                INSERT INTO reservation_time_policy_versions (
                    reservation_time_policy_version_id, store_id, version_number,
                    slot_interval_minutes, service_duration_minutes,
                    turnover_duration_minutes, status, effective_at, activated_at,
                    publication_requested_at, change_reason, created_at, updated_at
                ) VALUES (
                    80088, 30088, 9, 10, 60, 10, 'ACTIVE',
                    '2026-08-19 09:00:00.000000', '2026-08-19 09:00:00.000000',
                    '2026-08-19 08:00:00.000000', '예약금 생성 테스트',
                    '2026-08-19 08:00:00.000000', '2026-08-19 09:00:00.000000'
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO reservation_capacity_buckets (
                    reservation_capacity_bucket_id, store_id, service_date,
                    start_time, end_time, max_people, max_teams,
                    occupied_people, occupied_teams, min_party_size,
                    max_party_size, infants_allowed, policy_version
                ) VALUES (
                    50088, 30088, '2026-08-21', '09:00:00.000000',
                    '10:10:00.000000', 10, 5, 0, 0, 1, 10, TRUE, 7
                )
                """);
    }

    private static ReservationCreateRequest request() {
        return new ReservationCreateRequest(
                String.valueOf(STORE_ID),
                SERVICE_DATE,
                START_TIME,
                null,
                new ReservationPartyRequest(2, 0, 0),
                List.of());
    }

    private record TransactionObservation(
            String boundary,
            boolean transactionActive,
            String transactionName,
            long connectionId
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfig {

        @Bean
        @Primary
        MutableClock reservationDepositCreationRuntimeClock() {
            return new MutableClock(REQUESTED_AT);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            current.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return ZoneOffset.UTC.equals(zone) ? this : Clock.fixed(current.get(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
