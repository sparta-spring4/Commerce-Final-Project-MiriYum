package com.miriyum.domain.store.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.entity.StoreScheduleState;
import com.miriyum.domain.store.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.store.schedule.model.WeeklyInterval;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class StoreScheduleRepositoryIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private StoreScheduleStateRepository stateRepository;

    @Autowired
    private OperatingScheduleVersionRepository operatingRepository;

    @Autowired
    private ReservationScheduleVersionRepository reservationRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM store_schedule_state");
        jdbcTemplate.execute("DELETE FROM store_reservation_schedule_entries");
        jdbcTemplate.execute("DELETE FROM store_reservation_schedule_versions");
        jdbcTemplate.execute("DELETE FROM store_operating_schedule_entries");
        jdbcTemplate.execute("DELETE FROM store_operating_schedule_versions");
        storeRepository.deleteAll();
        operatorRepository.deleteAll();
    }

    @Test
    @Transactional
    void flywaySchemaMatchesImmutableVersionMappings() {
        long storeId = createStore();
        StoreScheduleState state = initializeAndLock(storeId);
        long versionNumber = state.allocateOperatingVersion();
        OperatingScheduleVersion version = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        storeId,
                        versionNumber,
                        List.of(business(9, 0, 18, 0, 540, 1080))));
        state.activateOperating(version.getId());
        entityManager.flush();
        entityManager.clear();

        OperatingScheduleVersion found =
                operatingRepository.findById(version.getId()).orElseThrow();
        StoreScheduleState foundState = stateRepository.findById(storeId).orElseThrow();

        assertThat(found.getVersionNumber()).isEqualTo(1);
        assertThat(found.getEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(entry.getWeekStartMinute()).isEqualTo(540);
            assertThat(entry.getWeekEndMinute()).isEqualTo(1080);
        });
        assertThat(foundState.getActiveOperatingScheduleVersionId())
                .isEqualTo(version.getId());
    }

    @Test
    @Transactional
    void activatingNewVersionKeepsPreviousVersionRowsUnchanged() {
        long storeId = createStore();
        StoreScheduleState state = initializeAndLock(storeId);
        OperatingScheduleVersion first = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        storeId,
                        state.allocateOperatingVersion(),
                        List.of(business(9, 0, 18, 0, 540, 1080))));
        state.activateOperating(first.getId());
        OperatingScheduleVersion second = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        storeId,
                        state.allocateOperatingVersion(),
                        List.of(business(10, 0, 19, 0, 600, 1140))));
        state.activateOperating(second.getId());
        entityManager.flush();
        entityManager.clear();

        List<OperatingScheduleVersion> versions =
                operatingRepository.findAllByStoreIdOrderByVersionNumber(storeId);

        assertThat(versions).extracting(OperatingScheduleVersion::getVersionNumber)
                .containsExactly(1L, 2L);
        assertThat(versions.get(0).getEntries().get(0).getStartTime())
                .isEqualTo(LocalTime.of(9, 0));
        assertThat(stateRepository.findById(storeId).orElseThrow()
                .getActiveOperatingScheduleVersionId()).isEqualTo(second.getId());
    }

    @Test
    @Transactional
    void operatingAndReservationCountersAdvanceIndependently() {
        long storeId = createStore();
        StoreScheduleState state = initializeAndLock(storeId);

        assertThat(state.allocateOperatingVersion()).isEqualTo(1);
        assertThat(state.allocateOperatingVersion()).isEqualTo(2);
        assertThat(state.allocateReservationVersion()).isEqualTo(1);
        assertThat(state.allocateReservationVersion()).isEqualTo(2);
    }

    @Test
    @Transactional
    void reservationVersionReferencesValidatedOperatingVersion() {
        long storeId = createStore();
        StoreScheduleState state = initializeAndLock(storeId);
        OperatingScheduleVersion operating = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        storeId,
                        state.allocateOperatingVersion(),
                        List.of(business(18, 0, 2, 0, 1080, 1560))));
        state.activateOperating(operating.getId());
        ReservationScheduleVersion reservation = reservationRepository.saveAndFlush(
                ReservationScheduleVersion.create(
                        storeId,
                        state.allocateReservationVersion(),
                        operating.getId(),
                        List.of(reservation(19, 0, 1, 0, 1140, 1500))));
        state.activateReservation(reservation.getId());
        entityManager.flush();
        entityManager.clear();

        ReservationScheduleVersion found =
                reservationRepository.findById(reservation.getId()).orElseThrow();

        assertThat(found.getValidatedOperatingVersionId()).isEqualTo(operating.getId());
        assertThat(found.getEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.getWeekStartMinute()).isEqualTo(1140);
            assertThat(entry.getWeekEndMinute()).isEqualTo(1500);
        });
    }

    @Test
    @Transactional
    void deletingStoreReferencedByScheduleStateIsRestricted() {
        long storeId = createStore();
        initializeAndLock(storeId);
        entityManager.flush();

        assertThatThrownBy(() -> {
            storeRepository.deleteById(storeId);
            storeRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    private StoreScheduleState initializeAndLock(long storeId) {
        stateRepository.initialize(storeId);
        return stateRepository.findForUpdateByStoreId(storeId).orElseThrow();
    }

    private long createStore() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(
                        "schedule-owner@example.com",
                        "hashed",
                        "운영자")).getId();
        return storeRepository.saveAndFlush(Store.create(
                operatorId,
                "1234567890",
                BusinessType.CAFE,
                "야간 매장",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of(),
                true,
                true,
                true)).getId();
    }

    private WeeklyInterval business(
            int startHour,
            int startMinute,
            int endHour,
            int endMinute,
            int weekStart,
            int weekEnd
    ) {
        return new WeeklyInterval(
                DayOfWeek.MONDAY,
                LocalTime.of(startHour, startMinute),
                LocalTime.of(endHour, endMinute),
                weekEnd > 1440,
                ScheduleIntervalKind.BUSINESS_HOURS,
                weekStart,
                weekEnd);
    }

    private WeeklyInterval reservation(
            int startHour,
            int startMinute,
            int endHour,
            int endMinute,
            int weekStart,
            int weekEnd
    ) {
        return new WeeklyInterval(
                DayOfWeek.MONDAY,
                LocalTime.of(startHour, startMinute),
                LocalTime.of(endHour, endMinute),
                weekEnd > 1440,
                ScheduleIntervalKind.RESERVATION_SLOT,
                weekStart,
                weekEnd);
    }
}
