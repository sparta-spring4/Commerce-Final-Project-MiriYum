package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyDraftRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationCancellationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationRequest.PublicationMode;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyAudit;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.schedule.dto.contract.StoreReservationWindowResult;
import com.miriyum.domain.schedule.service.StoreScheduleService;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        }
)
class ReservationTimePolicyPublicationIT {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout = 1"
        );
    }

    @Autowired
    private ReservationTimePolicyCommandFacade commandFacade;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationTimeResolutionService timeResolutionService;

    @Autowired
    private ReservationTimePolicyVersionRepository policyRepository;

    @MockitoSpyBean
    private ReservationTimePolicyAuditRepository auditRepository;

    @MockitoBean
    private StoreScheduleService storeScheduleService;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_audits");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_versions");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
    }

    @Test
    void draftPublicationCancellationAndReplayKeepOneCanonicalLifecycle() {
        OwnerStore owner = createStore();
        ReservationTimePolicyCommandResult<?> firstDraft = commandFacade.createDraft(
                owner.operatorId(),
                owner.storeId(),
                key(1),
                new ReservationTimePolicyDraftRequest(30, 90, 15)
        );
        ReservationTimePolicyCommandResult<?> replay = commandFacade.createDraft(
                owner.operatorId(),
                owner.storeId(),
                key(1),
                new ReservationTimePolicyDraftRequest(30, 90, 15)
        );
        commandFacade.publish(
                owner.operatorId(),
                owner.storeId(),
                1L,
                key(2),
                new ReservationTimePolicyPublicationRequest(
                        PublicationMode.IMMEDIATE,
                        null,
                        "첫 정책 즉시 적용"
                )
        );
        commandFacade.createDraft(
                owner.operatorId(),
                owner.storeId(),
                key(3),
                new ReservationTimePolicyDraftRequest(15, 120, 20)
        );
        Instant future = Instant.now().plusSeconds(120);
        commandFacade.publish(
                owner.operatorId(),
                owner.storeId(),
                2L,
                key(4),
                new ReservationTimePolicyPublicationRequest(
                        PublicationMode.SCHEDULED,
                        OffsetDateTime.ofInstant(future, ZoneOffset.UTC),
                        "다음 운영일부터 적용"
                )
        );
        commandFacade.cancelPublication(
                owner.operatorId(),
                owner.storeId(),
                2L,
                key(5),
                new ReservationTimePolicyPublicationCancellationRequest("적용 보류")
        );

        assertThat(firstDraft.data()).isEqualTo(replay.data());
        assertThat(policyRepository.findAll())
                .extracting(ReservationTimePolicyVersion::getStatus)
                .containsExactlyInAnyOrder(
                        ReservationTimePolicyStatus.ACTIVE,
                        ReservationTimePolicyStatus.DRAFT
                );
        assertThat(policyRepository.findAll())
                .filteredOn(policy -> policy.getVersionNumber() == 1L)
                .singleElement()
                .extracting(ReservationTimePolicyVersion::getChangeReason)
                .isEqualTo("첫 정책 즉시 적용");
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_time_policy_audits",
                Integer.class
        );
        assertThat(auditCount).isEqualTo(5);

        assertThatThrownBy(() -> commandFacade.createDraft(
                owner.operatorId(),
                owner.storeId(),
                key(1),
                new ReservationTimePolicyDraftRequest(60, 60, 0)
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void duePublicationActivatesExactlyOnceAndKeepsOneActive() {
        OwnerStore owner = createStore();
        commandFacade.createDraft(
                owner.operatorId(), owner.storeId(), key(10),
                new ReservationTimePolicyDraftRequest(30, 60, 15)
        );
        commandFacade.publish(
                owner.operatorId(), owner.storeId(), 1L, key(11),
                new ReservationTimePolicyPublicationRequest(
                        PublicationMode.IMMEDIATE, null, "기존 정책"
                )
        );
        commandFacade.createDraft(
                owner.operatorId(), owner.storeId(), key(12),
                new ReservationTimePolicyDraftRequest(30, 90, 15)
        );
        commandFacade.publish(
                owner.operatorId(), owner.storeId(), 2L, key(13),
                new ReservationTimePolicyPublicationRequest(
                        PublicationMode.SCHEDULED,
                        OffsetDateTime.ofInstant(
                                Instant.now().plusSeconds(120),
                                ZoneOffset.UTC
                        ),
                        "새 정책"
                )
        );
        long scheduledId = policyRepository.findAll().stream()
                .filter(policy -> policy.getVersionNumber() == 2L)
                .findFirst()
                .orElseThrow()
                .getId();
        jdbcTemplate.update(
                """
                        UPDATE reservation_time_policy_versions
                        SET effective_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
                        WHERE reservation_time_policy_version_id = ?
                        """,
                scheduledId
        );

        assertThat(reservationService.activateDueTimePolicy(scheduledId)).isTrue();
        assertThat(reservationService.activateDueTimePolicy(scheduledId)).isFalse();

        List<ReservationTimePolicyVersion> policies = policyRepository.findAll();
        assertThat(policies)
                .filteredOn(policy ->
                        policy.getStatus() == ReservationTimePolicyStatus.ACTIVE)
                .singleElement()
                .extracting(ReservationTimePolicyVersion::getVersionNumber)
                .isEqualTo(2L);
        Integer systemAuditCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM reservation_time_policy_audits
                        WHERE actor_type = 'SYSTEM'
                          AND target_version = 2
                        """,
                Integer.class
        );
        assertThat(systemAuditCount).isEqualTo(1);
        String systemAuditReason = jdbcTemplate.queryForObject(
                """
                        SELECT change_reason
                        FROM reservation_time_policy_audits
                        WHERE actor_type = 'SYSTEM'
                          AND target_version = 2
                        """,
                String.class
        );
        assertThat(systemAuditReason).isEqualTo("새 정책");
    }

    @Test
    void concurrentDraftsReceiveDistinctSequentialVersions() throws Exception {
        OwnerStore owner = createStore();
        CountDownLatch startGate = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ReservationTimePolicyCommandResult<?>> first = executor.submit(() -> {
                startGate.await();
                return commandFacade.createDraft(
                        owner.operatorId(), owner.storeId(), key(21),
                        new ReservationTimePolicyDraftRequest(15, 60, 0)
                );
            });
            Future<ReservationTimePolicyCommandResult<?>> second = executor.submit(() -> {
                startGate.await();
                return commandFacade.createDraft(
                        owner.operatorId(), owner.storeId(), key(22),
                        new ReservationTimePolicyDraftRequest(30, 90, 15)
                );
            });
            startGate.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).httpStatus()).isEqualTo(200);
            assertThat(second.get(20, TimeUnit.SECONDS).httpStatus()).isEqualTo(200);
        }

        assertThat(policyRepository.findAll())
                .extracting(ReservationTimePolicyVersion::getVersionNumber)
                .containsExactlyInAnyOrder(1L, 2L);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_time_policy_audits",
                Integer.class
        );
        assertThat(auditCount).isEqualTo(2);
    }

    @Test
    void concurrentScheduledPublicationsLeaveExactlyOneScheduledPolicy()
            throws Exception {
        OwnerStore owner = createStore();
        commandFacade.createDraft(
                owner.operatorId(), owner.storeId(), key(31),
                new ReservationTimePolicyDraftRequest(15, 60, 0)
        );
        commandFacade.createDraft(
                owner.operatorId(), owner.storeId(), key(32),
                new ReservationTimePolicyDraftRequest(30, 90, 15)
        );
        CountDownLatch startGate = new CountDownLatch(1);
        OffsetDateTime effectiveAt = OffsetDateTime.ofInstant(
                Instant.now().plusSeconds(120),
                ZoneOffset.UTC
        );

        List<PublicationOutcome> outcomes = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<PublicationOutcome> first = executor.submit(() -> {
                startGate.await();
                return publishScheduled(owner, 1L, key(33), effectiveAt);
            });
            Future<PublicationOutcome> second = executor.submit(() -> {
                startGate.await();
                return publishScheduled(owner, 2L, key(34), effectiveAt);
            });
            startGate.countDown();
            outcomes.add(first.get(20, TimeUnit.SECONDS));
            outcomes.add(second.get(20, TimeUnit.SECONDS));
        }

        assertThat(outcomes).containsExactlyInAnyOrder(
                PublicationOutcome.SUCCEEDED,
                PublicationOutcome.CONFLICT
        );
        assertThat(policyRepository.findAll())
                .filteredOn(policy ->
                        policy.getStatus() == ReservationTimePolicyStatus.SCHEDULED)
                .hasSize(1);
        assertThat(policyRepository.findAll())
                .filteredOn(policy ->
                        policy.getStatus() == ReservationTimePolicyStatus.DRAFT)
                .hasSize(1);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_time_policy_audits",
                Integer.class
        );
        assertThat(auditCount).isEqualTo(3);
    }

    @Test
    void auditFailureRollsBackPolicyAndIdempotencyClaim() {
        OwnerStore owner = createStore();
        doThrow(new DataIntegrityViolationException("forced audit failure"))
                .when(auditRepository)
                .save(any(ReservationTimePolicyAudit.class));

        assertThatThrownBy(() -> commandFacade.createDraft(
                owner.operatorId(), owner.storeId(), key(41),
                new ReservationTimePolicyDraftRequest(30, 90, 15)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(policyRepository.findAll()).isEmpty();
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_time_policy_audits",
                Integer.class
        );
        Integer idempotencyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands",
                Integer.class
        );
        assertThat(auditCount).isZero();
        assertThat(idempotencyCount).isZero();
    }

    @Test
    void dueScheduledPolicyMakesDatabaseBackedResolutionFailClosed() {
        OwnerStore owner = createStore();
        commandFacade.createDraft(
                owner.operatorId(), owner.storeId(), key(51),
                new ReservationTimePolicyDraftRequest(30, 60, 15)
        );
        commandFacade.publish(
                owner.operatorId(), owner.storeId(), 1L, key(52),
                new ReservationTimePolicyPublicationRequest(
                        PublicationMode.IMMEDIATE, null, "현재 정책"
                )
        );
        commandFacade.createDraft(
                owner.operatorId(), owner.storeId(), key(53),
                new ReservationTimePolicyDraftRequest(30, 90, 15)
        );
        commandFacade.publish(
                owner.operatorId(), owner.storeId(), 2L, key(54),
                new ReservationTimePolicyPublicationRequest(
                        PublicationMode.SCHEDULED,
                        OffsetDateTime.ofInstant(
                                Instant.now().plusSeconds(120),
                                ZoneOffset.UTC
                        ),
                        "교체 예정 정책"
                )
        );
        jdbcTemplate.update(
                """
                        UPDATE reservation_time_policy_versions
                        SET effective_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
                        WHERE store_id = ? AND version_number = 2
                        """,
                owner.storeId()
        );
        LocalDate serviceDate = LocalDate.of(2026, 8, 4);
        LocalTime startTime = LocalTime.of(18, 0);
        LocalDateTime requestedAt = LocalDateTime.of(serviceDate, startTime);
        given(storeScheduleService.resolveReservationWindows(
                List.of(owner.storeId()),
                serviceDate,
                startTime
        )).willReturn(List.of(StoreReservationWindowResult.accepting(
                owner.storeId(),
                "Asia/Seoul",
                requestedAt.minusHours(1),
                requestedAt.plusHours(1)
        )));

        ReservationTimeResolutionResult result = timeResolutionService
                .resolveReservationTimes(
                        List.of(owner.storeId()),
                        new ReservationTimeRequest(serviceDate, startTime, null)
                )
                .getFirst();

        assertThat(result.status())
                .isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
        assertThat(result.time()).isNull();
    }

    private PublicationOutcome publishScheduled(
            OwnerStore owner,
            long version,
            IdempotencyKey idempotencyKey,
            OffsetDateTime effectiveAt
    ) {
        try {
            commandFacade.publish(
                    owner.operatorId(),
                    owner.storeId(),
                    version,
                    idempotencyKey,
                    new ReservationTimePolicyPublicationRequest(
                            PublicationMode.SCHEDULED,
                            effectiveAt,
                            "동시 예약 게시"
                    )
            );
            return PublicationOutcome.SUCCEEDED;
        } catch (ServiceException exception) {
            assertThat(exception.getErrorCode())
                    .isEqualTo(ReservationErrorCode.TIME_POLICY_CONFLICT);
            return PublicationOutcome.CONFLICT;
        }
    }

    private OwnerStore createStore() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(
                        "reservation-policy@example.com",
                        "hashed",
                        "운영자"
                )
        ).getId();
        long storeId = storeRepository.saveAndFlush(Store.create(
                operatorId,
                "1234567890",
                "예약 정책 매장",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of(),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 8, 4, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"
        )).getId();
        return new OwnerStore(operatorId, storeId);
    }

    private IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format(
                java.util.Locale.ROOT,
                "550e8400-e29b-41d4-a716-%012d",
                suffix
        ));
    }

    private record OwnerStore(long operatorId, long storeId) {
    }

    private enum PublicationOutcome {
        SUCCEEDED,
        CONFLICT
    }
}
