package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureCommandResult;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingClosureJobItemRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingClosureJobRepository;
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
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.waiting.closure.initial-delay-ms=600000"
})
class WaitingClosureServiceIT {
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440202");

    @Container static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WaitingClosureService service;
    @Autowired WaitingTeamRepository teams;
    @Autowired WaitingActiveMembershipRepository memberships;
    @Autowired StoreRepository stores;
    @Autowired StoreOperatorAccountRepository operators;
    @Autowired JdbcTemplate jdbc;
    @Autowired WaitingClosureJobItemRepository items;
    @Autowired WaitingClosureJobRepository jobs;

    @BeforeEach
    void clean() {
        for (String table : new String[]{"waiting_status_events", "waiting_transition_audits",
                "waiting_closure_job_items", "waiting_closure_jobs", "waiting_active_memberships",
                "waiting_teams", "waiting_queue_sequences", "idempotency_commands",
                "store_tag_assignment", "stores", "store_operator_accounts", "consumer_accounts"}) {
            jdbc.execute("DELETE FROM " + table);
        }
    }

    @Test
    void realTransactionReplaysSameJobAndFixedItemsWithoutDuplicates() {
        Fixture fixture = fixture();

        WaitingClosureCommandResult first = service.startClosure(
                fixture.operatorId, fixture.storeId, KEY, 7L);
        WaitingClosureCommandResult replay = service.startClosure(
                fixture.operatorId, fixture.storeId, KEY, 7L);

        assertThat(first.httpStatus()).isEqualTo(202);
        assertThat(replay).isEqualTo(first);
        assertThat(count("waiting_closure_jobs")).isOne();
        assertThat(count("waiting_closure_job_items")).isOne();
        assertThat(count("idempotency_commands")).isOne();

        assertThatThrownBy(() -> service.startClosure(
                fixture.operatorId, fixture.storeId, KEY, 8L))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(count("waiting_closure_jobs")).isOne();
    }

    @Test
    void workerClosesActiveTeamAtomicallyExactlyOnceAndCompletesJob() {
        Fixture fixture = fixture();
        service.startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
        long itemId = service.claimPendingItems(100).getFirst();

        service.processClaimedItem(itemId);

        assertThat(jdbc.queryForObject("SELECT status FROM waiting_teams", String.class))
                .isEqualTo("CLOSED_BY_STORE");
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(count("waiting_transition_audits")).isOne();
        assertThat(count("waiting_status_events")).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_job_items", String.class))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_team_count FROM waiting_closure_jobs", Long.class))
                .isOne();
    }

    @Test
    void failedItemRollsBackThenRestartRecoveryRequeuesAndCompletes() {
        Fixture fixture = fixture();
        service.startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
        long itemId = service.claimPendingItems(100).getFirst();
        jdbc.execute("DELETE FROM waiting_active_memberships");

        assertThatThrownBy(() -> service.processClaimedItem(itemId))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_teams", String.class)).isEqualTo("WAITING");
        assertThat(count("waiting_transition_audits")).isZero();
        assertThat(count("waiting_status_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_job_items", String.class))
                .isEqualTo("PROCESSING");

        long teamId = jdbc.queryForObject("SELECT waiting_team_id FROM waiting_teams", Long.class);
        long consumerId = jdbc.queryForObject("SELECT consumer_account_id FROM waiting_teams", Long.class);
        memberships.saveAndFlush(WaitingActiveMembership.create(
                fixture.storeId, consumerId, teamId, Instant.now()));
        int attemptsBeforeRecovery = jdbc.queryForObject(
                "SELECT attempt_count FROM waiting_closure_job_items", Integer.class);
        service.recoverStrandedWork();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM waiting_closure_job_items", Integer.class))
                .isEqualTo(attemptsBeforeRecovery);
        long reclaimed = service.claimPendingItems(100).getFirst();
        service.processClaimedItem(reclaimed);
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("COMPLETED");
    }

    @Test
    void workerSkipsTeamThatBecameTerminalAfterSnapshot() {
        Fixture fixture = fixture();
        service.startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
        long itemId = service.claimPendingItems(100).getFirst();
        jdbc.execute("DELETE FROM waiting_active_memberships");
        jdbc.update("UPDATE waiting_teams SET status='CANCELLED', cancelled_at=NOW(6), version=version+1");

        service.processClaimedItem(itemId);

        assertThat(jdbc.queryForObject("SELECT status FROM waiting_teams", String.class)).isEqualTo("CANCELLED");
        assertThat(count("waiting_transition_audits")).isZero();
        assertThat(count("waiting_status_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("COMPLETED");
    }

    @Test
    void recoveryReconcilesExhaustedProcessingItemWithoutFourthClaim() {
        Fixture fixture = fixture();
        service.startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
        long itemId = service.claimPendingItems(100).getFirst();
        service.recordFailure(itemId, true);
        service.claimPendingItems(100);
        service.recordFailure(itemId, true);
        service.claimPendingItems(100);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM waiting_closure_job_items", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_job_items", String.class))
                .isEqualTo("PROCESSING");

        service.recoverStrandedWork();

        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_job_items", String.class))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM waiting_closure_job_items", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject(
                "SELECT reconciliation_required_team_count FROM waiting_closure_jobs", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM waiting_closure_jobs", Boolean.class))
                .isTrue();
        assertThat(service.claimPendingItems(100)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM waiting_closure_job_items", Integer.class))
                .isEqualTo(3);
    }

    private Fixture fixture() {
        long operatorId = operators.saveAndFlush(
                StoreOperatorAccount.create("closure@example.com", "hashed", "owner")).getId();
        long storeId = stores.saveAndFlush(Store.create(operatorId, "1234567899", BusinessType.CAFE,
                "Closure Store", "", Region.SEOUL, "Seoul", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul", LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        jdbc.update("INSERT INTO consumer_accounts (email,password_hash,name,status,created_at,updated_at) "
                + "VALUES ('closure-consumer@example.com','hashed','consumer','ACTIVE',NOW(6),NOW(6))");
        long consumerId = jdbc.queryForObject(
                "SELECT consumer_account_id FROM consumer_accounts WHERE email='closure-consumer@example.com'",
                Long.class);
        WaitingTeam team = teams.saveAndFlush(WaitingTeam.create(storeId, consumerId,
                LocalDate.of(2026, 8, 13), 2, WaitingSource.REMOTE, 1L, Instant.now().minusSeconds(60)));
        memberships.saveAndFlush(WaitingActiveMembership.create(
                storeId, consumerId, team.getId(), Instant.now().minusSeconds(60)));
        return new Fixture(operatorId, storeId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record Fixture(long operatorId, long storeId) {}
}
