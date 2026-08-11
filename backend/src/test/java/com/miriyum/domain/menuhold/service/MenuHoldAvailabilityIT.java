package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability.AvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.menu.dto.contract.MenuHoldSelectableMenu;
import com.miriyum.domain.menu.service.MenuHoldSelectionQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.menu.schedule.enabled=false"
        })
class MenuHoldAvailabilityIT {

    private static final long OPERATOR_ID = 20_075L;
    private static final long STORE_ID = 30_075L;
    private static final long AVAILABLE_MENU_ID = 40_075L;
    private static final long SOLD_OUT_MENU_ID = 40_076L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MenuHoldAvailabilityQueryService service;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean MenuHoldSelectionQueryService selectionQueryService;
    @MockitoBean ReservationService reservationService;

    @BeforeEach
    void resetAndSeed() {
        jdbcTemplate.execute("DELETE FROM menu_inventory_buckets");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        insertStoreAndMenus();
        insertBucket(50_075L, AVAILABLE_MENU_ID, "AVAILABLE");
        insertBucket(50_076L, SOLD_OUT_MENU_ID, "SOLD_OUT");

        given(selectionQueryService.findSelectableMenus(STORE_ID)).willReturn(List.of(
                new MenuHoldSelectableMenu(SOLD_OUT_MENU_ID, "Sold out", 9000),
                new MenuHoldSelectableMenu(AVAILABLE_MENU_ID, "Available", 4500)));
        given(reservationService.resolveReservationTimes(
                org.mockito.ArgumentMatchers.eq(List.of(STORE_ID)),
                org.mockito.ArgumentMatchers.any())).willReturn(List.of(
                        ReservationTimeResolutionResult.resolved(STORE_ID, resolvedTime())));
    }

    @Test
    void readsCurrentMysqlBucketsExcludesOnsiteAndNormalizesSoldOutQuantity() {
        var result = service.findAvailability(
                STORE_ID, SERVICE_DATE, LocalTime.of(18, 0), null);

        assertThat(result.items()).extracting(item -> item.menuId())
                .containsExactly(Long.toString(AVAILABLE_MENU_ID), Long.toString(SOLD_OUT_MENU_ID));
        assertThat(result.items().get(0).availableOnlineQuantity()).isEqualTo(5);
        assertThat(result.items().get(0).availabilityStatus())
                .isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(result.items().get(1).availableOnlineQuantity()).isZero();
        assertThat(result.items().get(1).availabilityStatus())
                .isEqualTo(AvailabilityStatus.SOLD_OUT);
    }

    @Test
    void returnsOnlyCandidateWithCurrentMysqlBucket() {
        jdbcTemplate.update(
                "DELETE FROM menu_inventory_buckets WHERE menu_id = ?",
                SOLD_OUT_MENU_ID);

        var result = service.findAvailability(
                STORE_ID, SERVICE_DATE, LocalTime.of(18, 0), null);

        assertThat(result.items()).extracting(item -> item.menuId())
                .containsExactly(Long.toString(AVAILABLE_MENU_ID));
        assertThat(result.items().getFirst().availableOnlineQuantity()).isEqualTo(5);
        assertThat(result.items().getFirst().availabilityStatus())
                .isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    private void insertStoreAndMenus() {
        jdbcTemplate.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (?, 'availability-owner@example.com', 'hash', '운영자', 'ACTIVE',
                    NOW(6), NOW(6))
                """, OPERATOR_ID);
        jdbcTemplate.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    business_type, name, description, region, address, store_category_code,
                    verification_status, operation_status, reservation_enabled,
                    menu_hold_enabled, pickup_enabled, time_zone_id,
                    applicant_self_attested_at, required_terms_agreed_at,
                    required_terms_version, created_at, updated_at
                ) VALUES (?, ?, '7500000000', 'OTHER', '가용성 매장', '설명', 'SEOUL',
                    '서울', 'ETC', 'APPROVED', 'OPEN', TRUE, TRUE, FALSE, 'Asia/Seoul',
                    NOW(6), NOW(6), 'STORE_ONBOARDING_REQUIRED_TERMS_V1', NOW(6), NOW(6))
                """, STORE_ID, OPERATOR_ID);
        insertMenu(AVAILABLE_MENU_ID);
        insertMenu(SOLD_OUT_MENU_ID);
    }

    private void insertMenu(long menuId) {
        jdbcTemplate.update("""
                INSERT INTO menus (
                    menu_id, store_id, next_version_number, published_version_number,
                    visibility, selling_status, retired, lock_version, created_at, updated_at
                ) VALUES (?, ?, 2, 1, 'VISIBLE', 'SELLING', FALSE, 0, NOW(6), NOW(6))
                """, menuId, STORE_ID);
    }

    private void insertBucket(long bucketId, long menuId, String status) {
        jdbcTemplate.update("""
                INSERT INTO menu_inventory_buckets (
                    menu_inventory_bucket_id, menu_id, service_date, start_time,
                    end_date, end_time, time_zone_id, inventory_policy_version,
                    total_supply, online_hold_capacity, online_hold_remaining,
                    onsite_capacity, onsite_remaining, shared_capacity, shared_remaining,
                    shared_online_allowed, availability_status, lock_version,
                    created_at, updated_at
                ) VALUES (?, ?, '2026-08-10', '18:00:00', '2026-08-10', '19:00:00',
                    'Asia/Seoul', 1, 105, 2, 2, 100, 100, 3, 3, TRUE, ?, 0,
                    NOW(6), NOW(6))
                """, bucketId, menuId, status);
    }

    private static ResolvedReservationTime resolvedTime() {
        return new ResolvedReservationTime(
                SERVICE_DATE, Instant.parse("2026-08-10T09:00:00Z"),
                Instant.parse("2026-08-10T10:00:00Z"),
                Instant.parse("2026-08-10T10:15:00Z"),
                "Asia/Seoul", 32400, 32400, 32400,
                30, 60, 15, STORE_ID, 1L);
    }
}
