package com.miriyum.domain.store.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.closure.entity.RegularClosureVersion;
import com.miriyum.domain.store.closure.repository.RegularClosureVersionRepository;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.entity.StoreScheduleState;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowResult;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowStatus;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalStatus;
import com.miriyum.domain.store.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.store.schedule.model.WeeklyInterval;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.menu.schedule.enabled=false"
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
    private RegularClosureVersionRepository regularClosureRepository;

    @Autowired
    private StoreScheduleService scheduleService;

    @Autowired
    private StoreServiceIntervalValidationService intervalValidationService;

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
        jdbcTemplate.execute("DELETE FROM store_closure_audit_events");
        jdbcTemplate.execute("DELETE FROM store_temporary_closures");
        jdbcTemplate.execute("DELETE FROM store_regular_closure_entries");
        jdbcTemplate.execute("DELETE FROM store_regular_closure_versions");
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
    void newOperatingVersionDeactivatesReservationValidatedAgainstPreviousVersion() {
        long storeId = createStore();
        StoreScheduleState state = initializeAndLock(storeId);
        OperatingScheduleVersion first = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        storeId,
                        state.allocateOperatingVersion(),
                        List.of(business(9, 0, 18, 0, 540, 1080))));
        state.activateOperating(first.getId());
        ReservationScheduleVersion reservation =
                reservationRepository.saveAndFlush(
                        ReservationScheduleVersion.create(
                                storeId,
                                state.allocateReservationVersion(),
                                first.getId(),
                                List.of(reservation(10, 0, 11, 0, 600, 660))));
        state.activateReservation(reservation.getId());

        OperatingScheduleVersion second = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        storeId,
                        state.allocateOperatingVersion(),
                        List.of(business(12, 0, 20, 0, 720, 1200))));
        state.activateOperating(second.getId());
        entityManager.flush();
        entityManager.clear();

        StoreScheduleState found = stateRepository.findById(storeId).orElseThrow();
        assertThat(found.getActiveOperatingScheduleVersionId())
                .isEqualTo(second.getId());
        assertThat(found.getActiveReservationScheduleVersionId()).isNull();
        assertThat(reservationRepository.findById(reservation.getId())).isPresent();
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

    @Test
    @Transactional
    void activeOperatingPointerCannotReferenceAnotherStoreVersion() {
        long firstStoreId = createStore(
                "first-schedule-owner@example.com", "1234567890");
        long secondStoreId = createStore(
                "second-schedule-owner@example.com", "1234567891");
        initializeAndLock(firstStoreId);
        StoreScheduleState secondState = initializeAndLock(secondStoreId);
        OperatingScheduleVersion secondVersion =
                operatingRepository.saveAndFlush(
                        OperatingScheduleVersion.create(
                                secondStoreId,
                                secondState.allocateOperatingVersion(),
                                List.of(business(9, 0, 18, 0, 540, 1080))));
        entityManager.flush();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE store_schedule_state
                SET active_operating_schedule_version_id = ?
                WHERE store_id = ?
                """,
                secondVersion.getId(),
                firstStoreId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void regularClosureEffectiveAtIsUniquePerStore() {
        long storeId = createStore();
        StoreScheduleState state = initializeAndLock(storeId);
        Instant effectiveAt = Instant.parse("2026-08-05T03:00:00Z");
        RegularClosureVersion first = RegularClosureVersion.createDraft(
                storeId,
                state.allocateRegularClosureVersion(),
                "Asia/Seoul",
                List.of(),
                List.of());
        first.schedule(effectiveAt, "첫 예약");
        regularClosureRepository.saveAndFlush(first);
        RegularClosureVersion competing = RegularClosureVersion.createDraft(
                storeId,
                state.allocateRegularClosureVersion(),
                "Asia/Seoul",
                List.of(),
                List.of());
        competing.schedule(effectiveAt, "동시 예약");

        assertThatThrownBy(() -> regularClosureRepository.saveAndFlush(competing))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void notEvaluatedConflictStatusCannotClaimZeroConflicts() {
        long storeId = createStore();
        StoreScheduleState state = initializeAndLock(storeId);
        OperatingScheduleVersion version = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        storeId,
                        state.allocateOperatingVersion(),
                        List.of(business(9, 0, 18, 0, 540, 1080))));
        entityManager.flush();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE store_operating_schedule_versions
                SET conflict_count = 0
                WHERE operating_schedule_version_id = ?
                """,
                version.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void reservationAvailabilityBatchQueriesLoadStatesAndOnlyActiveVersionsWithEntries() {
        long firstStoreId = createStore(
                "first-batch-schedule-owner@example.com", "1234567890");
        long secondStoreId = createStore(
                "second-batch-schedule-owner@example.com", "1234567891");
        StoreScheduleState firstState = initializeAndLock(firstStoreId);
        StoreScheduleState secondState = initializeAndLock(secondStoreId);
        OperatingScheduleVersion firstOperating = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        firstStoreId,
                        firstState.allocateOperatingVersion(),
                        List.of(business(9, 0, 18, 0, 540, 1080))));
        OperatingScheduleVersion secondOperating = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        secondStoreId,
                        secondState.allocateOperatingVersion(),
                        List.of(business(9, 0, 18, 0, 540, 1080))));
        firstState.activateOperating(firstOperating.getId());
        secondState.activateOperating(secondOperating.getId());

        ReservationScheduleVersion active = reservationRepository.saveAndFlush(
                ReservationScheduleVersion.create(
                        firstStoreId,
                        firstState.allocateReservationVersion(),
                        firstOperating.getId(),
                        List.of(reservation(10, 0, 11, 0, 600, 660))));
        ReservationScheduleVersion retired = reservationRepository.saveAndFlush(
                ReservationScheduleVersion.create(
                        secondStoreId,
                        secondState.allocateReservationVersion(),
                        secondOperating.getId(),
                        List.of(reservation(12, 0, 13, 0, 720, 780))));
        retired.retire();
        firstState.activateReservation(active.getId());
        secondState.activateReservation(retired.getId());
        entityManager.flush();
        entityManager.clear();

        List<StoreScheduleState> states =
                stateRepository.findAllByStoreIdIn(List.of(firstStoreId, secondStoreId));
        List<ReservationScheduleVersion> versions =
                reservationRepository.findActiveByIdsWithEntries(
                        List.of(active.getId(), retired.getId()),
                        ScheduleVersionStatus.ACTIVE);

        assertThat(states).extracting(StoreScheduleState::getStoreId)
                .containsExactlyInAnyOrder(firstStoreId, secondStoreId);
        assertThat(versions).singleElement().satisfies(version -> {
            assertThat(version.getId()).isEqualTo(active.getId());
            assertThat(version.getEntries()).hasSize(1);
        });
    }

    @Test
    @Transactional
    void reservationWindowContractWorksAcrossMidnightAndFailsClosedForInactivePointer() {
        long regularStoreId = createStore(
                "regular-window-owner@example.com", "1234567890");
        long overnightStoreId = createStore(
                "overnight-window-owner@example.com", "1234567891");
        long inactiveStoreId = createStore(
                "inactive-window-owner@example.com", "1234567892");
        StoreScheduleState regularState = initializeAndLock(regularStoreId);
        StoreScheduleState overnightState = initializeAndLock(overnightStoreId);
        StoreScheduleState inactiveState = initializeAndLock(inactiveStoreId);
        OperatingScheduleVersion regularOperating = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        regularStoreId,
                        regularState.allocateOperatingVersion(),
                        List.of(business(9, 0, 18, 0, 540, 1080))));
        OperatingScheduleVersion overnightOperating = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        overnightStoreId,
                        overnightState.allocateOperatingVersion(),
                        List.of(business(9, 0, 18, 0, 540, 1080))));
        OperatingScheduleVersion inactiveOperating = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        inactiveStoreId,
                        inactiveState.allocateOperatingVersion(),
                        List.of(business(9, 0, 18, 0, 540, 1080))));
        regularState.activateOperating(regularOperating.getId());
        overnightState.activateOperating(overnightOperating.getId());
        inactiveState.activateOperating(inactiveOperating.getId());
        ReservationScheduleVersion regular = reservationRepository.saveAndFlush(
                ReservationScheduleVersion.create(
                        regularStoreId,
                        regularState.allocateReservationVersion(),
                        regularOperating.getId(),
                        List.of(reservation(10, 0, 11, 0, 600, 660))));
        ReservationScheduleVersion overnight = reservationRepository.saveAndFlush(
                ReservationScheduleVersion.create(
                        overnightStoreId,
                        overnightState.allocateReservationVersion(),
                        overnightOperating.getId(),
                        List.of(new WeeklyInterval(
                                DayOfWeek.SUNDAY,
                                LocalTime.of(23, 0),
                                LocalTime.of(1, 0),
                                true,
                                ScheduleIntervalKind.RESERVATION_SLOT,
                                10020,
                                10140))));
        ReservationScheduleVersion inactive = reservationRepository.saveAndFlush(
                ReservationScheduleVersion.create(
                        inactiveStoreId,
                        inactiveState.allocateReservationVersion(),
                        inactiveOperating.getId(),
                        List.of(reservation(10, 0, 11, 0, 600, 660))));
        inactive.retire();
        regularState.activateReservation(regular.getId());
        overnightState.activateReservation(overnight.getId());
        inactiveState.activateReservation(inactive.getId());
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(org.hibernate.SessionFactory.class)
                .getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<StoreReservationWindowResult> results =
                scheduleService.resolveReservationWindows(
                        List.of(
                                overnightStoreId,
                                regularStoreId,
                                inactiveStoreId,
                                overnightStoreId),
                        java.time.LocalDate.of(2026, 8, 3),
                        LocalTime.of(0, 30));

        assertThat(results).extracting(StoreReservationWindowResult::storeId)
                .containsExactly(
                        overnightStoreId,
                        regularStoreId,
                        inactiveStoreId,
                        overnightStoreId);
        assertThat(results).extracting(StoreReservationWindowResult::status)
                .containsExactly(
                        StoreReservationWindowStatus.ACCEPTING,
                        StoreReservationWindowStatus.NOT_ACCEPTING,
                        StoreReservationWindowStatus.NOT_ACCEPTING,
                        StoreReservationWindowStatus.ACCEPTING);
        assertThat(results.getFirst().windowStartAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 2, 23, 0));
        assertThat(results.getFirst().windowEndAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 3, 1, 0));
        assertThat(results.getLast()).isEqualTo(results.getFirst());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2L);
    }

    @Test
    @Transactional
    void serviceIntervalBatchUsesSixQueriesAndPreservesDuplicates() {
        long firstStoreId = createIntervalStore(
                "first-service-interval-owner@example.com", "1234567894", 21);
        long secondStoreId = createIntervalStore(
                "second-service-interval-owner@example.com", "1234567895", 20);
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        StoreServiceIntervalRequest first = new StoreServiceIntervalRequest(
                firstStoreId, Instant.parse("2026-08-03T09:00:00Z"), Instant.parse("2026-08-03T09:45:00Z"));
        StoreServiceIntervalRequest second = new StoreServiceIntervalRequest(
                secondStoreId, Instant.parse("2026-08-03T09:00:00Z"), Instant.parse("2026-08-03T10:15:00Z"));

        var results = intervalValidationService.validateServiceIntervals(List.of(first, second, first));

        assertThat(results).hasSize(3).extracting(result -> result.storeId())
                .containsExactly(firstStoreId, secondStoreId, firstStoreId);
        assertThat(results).extracting(result -> result.status()).containsExactly(
                StoreServiceIntervalStatus.ACCEPTING,
                StoreServiceIntervalStatus.ACCEPTING,
                StoreServiceIntervalStatus.ACCEPTING);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(6L);
    }

    @Test
    @Transactional
    void dueQueriesReturnOnlyEachStoresEarliestCandidate() {
        Instant now = Instant.parse("2026-07-31T03:00:00Z");
        long saturatedStoreId = createStore(
                "saturated-schedule-owner@example.com", "1234567890");
        long otherStoreId = createStore(
                "other-schedule-owner@example.com", "1234567891");
        StoreScheduleState saturatedState =
                initializeAndLock(saturatedStoreId);
        StoreScheduleState otherState = initializeAndLock(otherStoreId);
        OperatingScheduleVersion saturatedBaseline =
                operatingRepository.saveAndFlush(
                        OperatingScheduleVersion.create(
                                saturatedStoreId,
                                saturatedState.allocateOperatingVersion(),
                                List.of(business(9, 0, 18, 0, 540, 1080))));
        OperatingScheduleVersion otherBaseline = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(
                        otherStoreId,
                        otherState.allocateOperatingVersion(),
                        List.of(business(9, 0, 18, 0, 540, 1080))));
        saturatedState.activateOperating(saturatedBaseline.getId());
        otherState.activateOperating(otherBaseline.getId());

        List<OperatingScheduleVersion> saturatedOperating = new ArrayList<>();
        List<ReservationScheduleVersion> saturatedReservation = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            Instant effectiveAt = now.minusSeconds(200L - index);
            OperatingScheduleVersion operating =
                    OperatingScheduleVersion.createDraft(
                            saturatedStoreId,
                            saturatedState.allocateOperatingVersion(),
                            "Asia/Seoul",
                            List.of(business(9, 0, 18, 0, 540, 1080)));
            operating.schedule(effectiveAt, "포화 매장 영업시간");
            saturatedOperating.add(operating);
            ReservationScheduleVersion reservation =
                    ReservationScheduleVersion.createDraft(
                            saturatedStoreId,
                            saturatedState.allocateReservationVersion(),
                            saturatedBaseline.getId(),
                            "Asia/Seoul",
                            List.of(reservation(10, 0, 11, 0, 600, 660)));
            reservation.schedule(effectiveAt, "포화 매장 예약시간");
            saturatedReservation.add(reservation);
        }
        operatingRepository.saveAllAndFlush(saturatedOperating);
        reservationRepository.saveAllAndFlush(saturatedReservation);

        OperatingScheduleVersion otherOperating =
                OperatingScheduleVersion.createDraft(
                        otherStoreId,
                        otherState.allocateOperatingVersion(),
                        "Asia/Seoul",
                        List.of(business(9, 0, 18, 0, 540, 1080)));
        otherOperating.schedule(now.minusSeconds(50), "다른 매장 영업시간");
        operatingRepository.saveAndFlush(otherOperating);
        ReservationScheduleVersion otherReservation =
                ReservationScheduleVersion.createDraft(
                        otherStoreId,
                        otherState.allocateReservationVersion(),
                        otherBaseline.getId(),
                        "Asia/Seoul",
                        List.of(reservation(10, 0, 11, 0, 600, 660)));
        otherReservation.schedule(now.minusSeconds(50), "다른 매장 예약시간");
        reservationRepository.saveAndFlush(otherReservation);

        List<OperatingScheduleVersion> operatingCandidates =
                operatingRepository.findEarliestDuePerStore(
                        ScheduleVersionStatus.SCHEDULED,
                        now,
                        PageRequest.of(0, 100));
        List<ReservationScheduleVersion> reservationCandidates =
                reservationRepository.findEarliestDuePerStore(
                        ScheduleVersionStatus.SCHEDULED,
                        now,
                        PageRequest.of(0, 100));

        assertThat(operatingCandidates)
                .extracting(OperatingScheduleVersion::getId)
                .containsExactly(
                        saturatedOperating.getFirst().getId(),
                        otherOperating.getId());
        assertThat(reservationCandidates)
                .extracting(ReservationScheduleVersion::getId)
                .containsExactly(
                        saturatedReservation.getFirst().getId(),
                        otherReservation.getId());
    }

    private StoreScheduleState initializeAndLock(long storeId) {
        stateRepository.initialize(storeId);
        return stateRepository.findForUpdateByStoreId(storeId).orElseThrow();
    }

    private long createIntervalStore(String email, String registrationNumber, int businessEndHour) {
        long storeId = createStore(email, registrationNumber);
        StoreScheduleState state = initializeAndLock(storeId);
        OperatingScheduleVersion operating = operatingRepository.saveAndFlush(
                OperatingScheduleVersion.create(storeId, state.allocateOperatingVersion(),
                        List.of(business(17, 0, businessEndHour, 30, 1020,
                                businessEndHour * 60 + 30))));
        state.activateOperating(operating.getId());
        ReservationScheduleVersion reservation = reservationRepository.saveAndFlush(
                ReservationScheduleVersion.create(storeId, state.allocateReservationVersion(), operating.getId(),
                        List.of(reservation(18, 0, 19, 30, 1080, 1170))));
        state.activateReservation(reservation.getId());
        RegularClosureVersion regular = regularClosureRepository.saveAndFlush(
                RegularClosureVersion.createDraft(storeId, state.allocateRegularClosureVersion(),
                        "Asia/Seoul", List.of(), List.of()));
        regular.activate(Instant.parse("2026-08-01T00:00:00Z"), "빈 휴무표 게시");
        state.activateRegularClosure(regular.getId());
        return storeId;
    }

    private long createStore() {
        return createStore("schedule-owner@example.com", "1234567890");
    }

    private long createStore(String email, String businessRegistrationNumber) {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(
                        email,
                        "hashed",
                        "운영자")).getId();
        return storeRepository.saveAndFlush(Store.create(
                operatorId,
                businessRegistrationNumber,
                BusinessType.CAFE,
                "야간 매장",
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
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
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
