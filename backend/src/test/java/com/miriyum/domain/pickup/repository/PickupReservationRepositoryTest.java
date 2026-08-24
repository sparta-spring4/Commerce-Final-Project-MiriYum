package com.miriyum.domain.pickup.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.pickup.entity.PickupItemSnapshot;
import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.entity.PickupStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class PickupReservationRepositoryTest {

    private static final long CONSUMER_ID = 10_001L;
    private static final long OTHER_CONSUMER_ID = 10_002L;
    private static final long OPERATOR_ID = 20_001L;
    private static final long STORE_ID = 30_001L;
    private static final long OTHER_STORE_ID = 30_002L;
    private static final long MENU_ID = 40_001L;
    private static final long BUCKET_ID = 50_001L;
    private static final Instant CREATED_AT = Instant.parse("2026-08-09T01:00:00Z");

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
    private PickupReservationRepository pickupReservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void resetAndSeedParents() {
        jdbcTemplate.execute("DELETE FROM pickup_reservation_items");
        jdbcTemplate.execute("DELETE FROM pickup_reservations");
        jdbcTemplate.execute("DELETE FROM menu_inventory_buckets");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");

        insertConsumer(CONSUMER_ID, "pickup-owner@example.com");
        insertConsumer(OTHER_CONSUMER_ID, "pickup-other@example.com");
        insertOperator();
        insertStore(STORE_ID, "1234567890", "픽업 매장");
        insertStore(OTHER_STORE_ID, "1234567891", "다른 매장");
        insertMenuAndBucket();
    }

    @Test
    @DisplayName("픽업 예약과 실제 확보 버킷 항목을 함께 저장하고 다시 읽는다")
    void persistsReservationAndItems() {
        PickupReservation saved = pickupReservationRepository.saveAndFlush(confirmedPickup(
                CONSUMER_ID, STORE_ID, "pickup-acquire-1"));

        PickupReservation found = pickupReservationRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(PickupStatus.CONFIRMED);
        assertThat(found.getAcquireOperationId()).isEqualTo("pickup-acquire-1");
        assertThat(found.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getMenuId()).isEqualTo(MENU_ID);
            assertThat(item.getMenuInventoryBucketId()).isEqualTo(BUCKET_ID);
        });
    }

    @Test
    @DisplayName("사용자와 매장 범위를 ID와 함께 조회해 다른 소유 범위에는 노출하지 않는다")
    void scopesLookupByConsumerAndStore() {
        PickupReservation saved = pickupReservationRepository.saveAndFlush(confirmedPickup(
                CONSUMER_ID, STORE_ID, "pickup-acquire-2"));

        assertThat(pickupReservationRepository.findByIdAndConsumerAccountId(
                saved.getId(), CONSUMER_ID)).isPresent();
        assertThat(pickupReservationRepository.findByIdAndConsumerAccountId(
                saved.getId(), OTHER_CONSUMER_ID)).isEmpty();
        assertThat(pickupReservationRepository.findByIdAndStoreId(
                saved.getId(), STORE_ID)).isPresent();
        assertThat(pickupReservationRepository.findByIdAndStoreId(
                saved.getId(), OTHER_STORE_ID)).isEmpty();
    }

    @Test
    @Transactional
    void bulkFetchesPagedReservationsWithItemsInOneRepositoryCall() {
        PickupReservation first = pickupReservationRepository.save(confirmedPickup(
                CONSUMER_ID, STORE_ID, "pickup-list-1"));
        PickupReservation second = pickupReservationRepository.save(confirmedPickup(
                OTHER_CONSUMER_ID, STORE_ID, "pickup-list-2"));
        pickupReservationRepository.flush();
        entityManager.clear();

        List<PickupReservation> found = pickupReservationRepository
                .findAllWithItemsByIdIn(List.of(first.getId(), second.getId()));

        assertThat(found).hasSize(2)
                .allSatisfy(pickup -> assertThat(pickup.getItems()).hasSize(1));
    }

    @Test
    @Transactional
    @DisplayName("상태 변경 경로는 사용자 범위의 픽업 aggregate를 잠금 조회한다")
    void locksPickupInsideConsumerScope() {
        PickupReservation saved = pickupReservationRepository.saveAndFlush(confirmedPickup(
                CONSUMER_ID, STORE_ID, "pickup-acquire-3"));

        PickupReservation locked = pickupReservationRepository
                .findByIdAndConsumerAccountIdForUpdate(saved.getId(), CONSUMER_ID)
                .orElseThrow();
        locked.cancelByConsumer(null, CREATED_AT.plusSeconds(60));
        pickupReservationRepository.flush();

        assertThat(locked.getStatus()).isEqualTo(PickupStatus.CANCELLED);
    }

    @Test
    @DisplayName("최초 확보 operation ID는 픽업 예약 전체에서 고유하다")
    void rejectsDuplicateAcquireOperationId() {
        pickupReservationRepository.saveAndFlush(confirmedPickup(
                CONSUMER_ID, STORE_ID, "pickup-acquire-duplicate"));

        assertThatThrownBy(() -> pickupReservationRepository.saveAndFlush(confirmedPickup(
                OTHER_CONSUMER_ID, STORE_ID, "pickup-acquire-duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static PickupReservation confirmedPickup(
            long consumerId,
            long storeId,
            String operationId
    ) {
        return PickupReservation.confirm(
                consumerId,
                storeId,
                "픽업 매장",
                "Asia/Seoul",
                LocalDate.of(2026, 8, 10),
                LocalTime.NOON,
                Instant.parse("2026-08-10T03:00:00Z"),
                operationId,
                List.of(new PickupItemSnapshot(
                        MENU_ID, BUCKET_ID, 2L, "바질 파스타", 12_000, 3L,
                        LocalDate.of(2026, 8, 10), LocalTime.NOON,
                        LocalDate.of(2026, 8, 10), LocalTime.of(14, 0), 2)),
                CREATED_AT
        );
    }

    private void insertConsumer(long consumerId, String email) {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status,
                    created_at, updated_at
                ) VALUES (?, ?, 'hash', '픽업사용자', 'ACTIVE', NOW(6), NOW(6))
                """, consumerId, email);
    }

    private void insertOperator() {
        jdbcTemplate.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (?, 'pickup-owner-store@example.com', 'hash', '운영자', 'ACTIVE',
                    NOW(6), NOW(6))
                """, OPERATOR_ID);
    }

    private void insertStore(long storeId, String businessNumber, String name) {
        jdbcTemplate.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    name, description, region, address, store_category_code,
                    verification_status, operation_status, reservation_enabled,
                    menu_hold_enabled, pickup_enabled, time_zone_id,
                    applicant_self_attested_at, required_terms_agreed_at,
                    required_terms_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, '설명', 'SEOUL', '서울', 'ETC',
                    'APPROVED', 'OPEN', TRUE, TRUE, TRUE, 'Asia/Seoul',
                    NOW(6), NOW(6), 'STORE_ONBOARDING_REQUIRED_TERMS_V1', NOW(6), NOW(6))
                """, storeId, OPERATOR_ID, businessNumber, name);
    }

    private void insertMenuAndBucket() {
        jdbcTemplate.update("""
                INSERT INTO menus (
                    menu_id, store_id, next_version_number, published_version_number,
                    visibility, selling_status, retired, lock_version, created_at, updated_at
                ) VALUES (?, ?, 3, 2, 'VISIBLE', 'SELLING', FALSE, 0, NOW(6), NOW(6))
                """, MENU_ID, STORE_ID);
        jdbcTemplate.update("""
                INSERT INTO menu_inventory_buckets (
                    menu_inventory_bucket_id, menu_id, service_date, start_time,
                    end_date, end_time, time_zone_id, inventory_policy_version,
                    total_supply, online_hold_capacity, online_hold_remaining,
                    onsite_capacity, onsite_remaining, shared_capacity, shared_remaining,
                    shared_online_allowed, availability_status, lock_version,
                    created_at, updated_at
                ) VALUES (?, ?, '2026-08-10', '12:00:00', '2026-08-10', '13:00:00',
                    'Asia/Seoul', 3, 10, 10, 10, 0, 0, 0, 0, FALSE, 'AVAILABLE', 0,
                    NOW(6), NOW(6))
                """, BUCKET_ID, MENU_ID);
    }
}
