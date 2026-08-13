package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingQueueSequence;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingQueueSequenceRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        }
)
class WaitingCommandFacadeIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 13);
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440101");

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
    private WaitingCommandFacade facade;

    @Autowired
    private WaitingTeamRepository teamRepository;

    @Autowired
    private WaitingQueueSequenceRepository sequenceRepository;

    @Autowired
    private WaitingActiveMembershipRepository membershipRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM waiting_status_events");
        jdbcTemplate.execute("DELETE FROM waiting_transition_audits");
        jdbcTemplate.execute("DELETE FROM waiting_closure_job_items");
        jdbcTemplate.execute("DELETE FROM waiting_closure_jobs");
        jdbcTemplate.execute("DELETE FROM waiting_active_memberships");
        jdbcTemplate.execute("DELETE FROM waiting_teams");
        jdbcTemplate.execute("DELETE FROM waiting_queue_sequences");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
    }

    @Test
    @DisplayName("a real executor replay returns the stored response without a second transition")
    void sameKeyAndFingerprintReplaysPersistedResultWithoutSecondTransition() {
        Fixture fixture = createFixture("waiting-replay@example.com", "1234567801");

        WaitingCommandResult first = facade.call(
                fixture.operatorId(), fixture.storeId(), fixture.teamId(), KEY,
                new WaitingTeamTransitionRequest(0L));
        WaitingCommandResult replay = facade.call(
                fixture.operatorId(), fixture.storeId(), fixture.teamId(), KEY,
                new WaitingTeamTransitionRequest(0L));

        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(first.data().status()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(first.data().version()).isEqualTo(1L);
        assertThat(replay).isEqualTo(first);
        assertPersistedSingleSuccessfulTransition(fixture.teamId());
    }

    @Test
    @DisplayName("the same key with a different expectedVersion is COMMON_007")
    void sameKeyAndDifferentVersionConflictsWithoutChangingStoredResult() {
        Fixture fixture = createFixture("waiting-conflict@example.com", "1234567802");
        WaitingCommandResult first = facade.call(
                fixture.operatorId(), fixture.storeId(), fixture.teamId(), KEY,
                new WaitingTeamTransitionRequest(0L));

        assertThatThrownBy(() -> facade.call(
                fixture.operatorId(), fixture.storeId(), fixture.teamId(), KEY,
                new WaitingTeamTransitionRequest(1L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        assertThat(first.data().version()).isEqualTo(1L);
        assertPersistedSingleSuccessfulTransition(fixture.teamId());
    }

    @Test
    @DisplayName("a failed transition rolls back its idempotency claim")
    void failedTransitionDoesNotLeaveSucceededIdempotencyRecord() {
        Fixture fixture = createFixture("waiting-rollback@example.com", "1234567803");

        assertThatThrownBy(() -> facade.call(
                fixture.operatorId(), fixture.storeId(), fixture.teamId(), KEY,
                new WaitingTeamTransitionRequest(99L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT));

        WaitingTeam stored = teamRepository.findById(fixture.teamId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(WaitingTeamStatus.WAITING);
        assertThat(stored.getVersion()).isZero();
        assertThat(count("idempotency_commands")).isZero();
        assertThat(count("waiting_transition_audits")).isZero();
        assertThat(count("waiting_status_events")).isZero();
    }

    private void assertPersistedSingleSuccessfulTransition(long teamId) {
        WaitingTeam stored = teamRepository.findById(teamId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(stored.getVersion()).isEqualTo(1L);
        assertThat(count("waiting_transition_audits")).isOne();
        assertThat(count("waiting_status_events")).isOne();
        assertThat(count("idempotency_commands")).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM idempotency_commands",
                String.class)).isEqualTo("SUCCEEDED");
    }

    private Fixture createFixture(String email, String registrationNumber) {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(email, "hashed", "owner")
        ).getId();
        long storeId = storeRepository.saveAndFlush(Store.create(
                operatorId,
                registrationNumber,
                BusinessType.CAFE,
                "Waiting Store",
                "",
                Region.SEOUL,
                "Seoul",
                "CAFE_BAKERY",
                Set.of(),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"
        )).getId();
        long consumerId = createConsumer(email);
        Instant createdAt = Instant.now().minusSeconds(60);
        WaitingQueueSequence sequence = WaitingQueueSequence.create(storeId, BUSINESS_DATE);
        sequence.allocate();
        sequenceRepository.saveAndFlush(sequence);
        long teamId = teamRepository.saveAndFlush(WaitingTeam.create(
                storeId,
                consumerId,
                BUSINESS_DATE,
                2,
                WaitingSource.REMOTE,
                1L,
                createdAt
        )).getId();
        membershipRepository.saveAndFlush(WaitingActiveMembership.create(
                storeId, consumerId, teamId, createdAt));
        return new Fixture(operatorId, storeId, teamId);
    }

    private long createConsumer(String operatorEmail) {
        String email = "consumer-" + operatorEmail;
        jdbcTemplate.update(
                """
                        INSERT INTO consumer_accounts (
                            email, password_hash, name, status, created_at, updated_at
                        ) VALUES (?, 'hashed', 'consumer', 'ACTIVE', NOW(6), NOW(6))
                        """,
                email
        );
        return jdbcTemplate.queryForObject(
                "SELECT consumer_account_id FROM consumer_accounts WHERE email = ?",
                Long.class,
                email
        );
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private record Fixture(long operatorId, long storeId, long teamId) {
    }
}
