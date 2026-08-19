package com.miriyum.domain.reservation.waiting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.ExpectedVersionRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.InvitationAcceptanceRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.TransferProposalRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerCommandFacade;
import com.miriyum.domain.reservation.waiting.service.WaitingCreationService;
import com.miriyum.domain.reservation.waiting.service.WaitingLocationProofService;
import com.miriyum.domain.reservation.waiting.service.WaitingOperatingInterval;
import com.miriyum.domain.reservation.waiting.service.WaitingOperatingIntervalPort;
import com.miriyum.domain.reservation.waiting.service.WaitingPartyService;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@Timeout(60)
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.time-policy.activation-enabled=false",
        "miriyum.waiting.closure.initial-delay-ms=600000",
        "miriyum.waiting.compensation.initial-delay-ms=600000",
        "miriyum.payment.cursor-secret=test-history-cursor-secret-with-enough-entropy",
        "miriyum.payment.portone.api-secret=test-api-secret",
        "miriyum.payment.portone.webhook-secret=whsec_dGVzdC1zZWNyZXQ=",
        "miriyum.payment.portone.store-id=store-1"
})
class WaitingPartyConcurrencyIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 19);
    @Container static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WaitingCreationService creation;
    @Autowired WaitingConsumerCommandFacade consumers;
    @Autowired WaitingPartyService parties;
    @Autowired WaitingLocationProofService locations;
    @Autowired WaitingTeamRepository teams;
    @Autowired WaitingActiveMembershipRepository memberships;
    @Autowired StoreRepository stores;
    @Autowired StoreOperatorAccountRepository operators;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @MockitoBean WaitingOperatingIntervalPort intervals;

    @BeforeEach
    void clean() {
        when(intervals.lockCurrent(anyLong(), any(LocalDate.class), any(Instant.class)))
                .thenAnswer(invocation -> List.of(openInterval(
                        invocation.getArgument(0), invocation.getArgument(1))));
        for (String table : new String[]{"waiting_party_audits",
                "waiting_representative_transfer_offers", "waiting_party_invitations",
                "waiting_location_proof_sessions", "waiting_status_events",
                "waiting_transition_audits", "waiting_active_memberships", "waiting_teams",
                "waiting_queue_sequences", "idempotency_commands", "waiting_setting_audits",
                "waiting_settings", "store_tag_assignment", "stores",
                "store_operator_accounts", "consumer_accounts"}) {
            jdbc.execute("DELETE FROM " + table);
        }
    }

    @Test
    void rawLocationSentinelsNeverReachRowsResponsesOrLogs() {
        long storeId = fixtureStore();
        long accountId = createConsumer();
        jdbc.update("UPDATE stores SET geocoding_status='VERIFIED', latitude=?, longitude=?, "
                        + "verified_address='verified fixture', geocoding_verified_at=NOW(6), "
                        + "geocoding_address_version=address_version WHERE store_id=?",
                new BigDecimal("37.500000000000000"),
                new BigDecimal("127.000000000000000"), storeId);
        String latitude = "37.500123456789012";
        String longitude = "127.000123456789012";
        String accuracy = "17.409123";
        String measured = "2026-08-19T03:04:05.409123Z";
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        Object response;
        try {
            response = locations.issue(accountId, storeId, new WaitingLocationProofContracts.Request(
                    WaitingLocationProofContracts.MeasurementStatus.MEASURED,
                    new BigDecimal(latitude), new BigDecimal(longitude), new BigDecimal(accuracy),
                    Instant.parse(measured), WaitingLocationProofContracts.IntegrityStatus.CLEAR));
        } finally {
            root.detachAppender(appender);
        }
        String persisted = jdbc.queryForObject("SELECT CONCAT_WS('|', result_category, "
                + "accuracy_category, policy_version, store_coordinate_version, issued_at, judged_at, "
                + "expires_at, COALESCE(consumed_at,'')) FROM waiting_location_proof_sessions",
                String.class);
        String idempotencyPayloads = jdbc.queryForObject(
                "SELECT COALESCE(GROUP_CONCAT(result_payload), '') FROM idempotency_commands",
                String.class);
        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + '|' + right);
        for (String sentinel : List.of(latitude, longitude, accuracy, measured)) {
            assertThat(String.valueOf(response)).doesNotContain(sentinel);
            assertThat(persisted).doesNotContain(sentinel);
            assertThat(idempotencyPayloads).doesNotContain(sentinel);
            assertThat(logs).doesNotContain(sentinel);
        }
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='waiting_location_proof_sessions'",
                String.class)).noneMatch(name -> Set.of("latitude", "longitude", "accuracy_meters",
                "distance_meters", "measured_at").contains(name));
    }

    @Test
    void sameInvitationAcceptedInParallelCreatesOneMembership() throws Exception {
        long storeId = fixtureStore();
        long representative = createConsumer();
        long candidate = createConsumer();
        long teamId = createTeam(storeId, representative, 2);
        String code = parties.issueInvitation(representative, teamId, key(1),
                new ExpectedVersionRequest(0L)).data().invitationCode();

        var attempts = runTogether(2, index -> parties.acceptInvitation(candidate,
                key(10 + index), new InvitationAcceptanceRequest(code)));

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(memberships.countByWaitingTeamId(teamId)).isEqualTo(2L);
        assertThat(memberships.findByConsumerAccountId(candidate)).isPresent();
    }

    @Test
    void sameIdempotencyKeyAcrossDifferentPartyCommandsCreatesDistinctAudits() {
        long storeId = fixtureStore();
        long representative = createConsumer();
        long candidate = createConsumer();
        long teamId = createTeam(storeId, representative, 2);
        IdempotencyKey reusedKey = key(15);

        String code = parties.issueInvitation(representative, teamId, reusedKey,
                new ExpectedVersionRequest(0L)).data().invitationCode();
        parties.acceptInvitation(candidate, reusedKey,
                new InvitationAcceptanceRequest(code));

        assertThat(memberships.findByConsumerAccountId(candidate)).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waiting_party_audits "
                + "WHERE waiting_team_id=?", Long.class, teamId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT command_id) "
                + "FROM waiting_party_audits WHERE waiting_team_id=?", Long.class, teamId))
                .isEqualTo(2L);
    }

    @Test
    void oneAccountAcceptingDifferentTeamsInParallelOccupiesOnlyOneTeam() throws Exception {
        long storeId = fixtureStore();
        long firstRep = createConsumer();
        long secondRep = createConsumer();
        long candidate = createConsumer();
        long firstTeam = createTeam(storeId, firstRep, 2);
        long secondTeam = createTeam(storeId, secondRep, 2);
        List<String> codes = List.of(
                parties.issueInvitation(firstRep, firstTeam, key(20),
                        new ExpectedVersionRequest(0L)).data().invitationCode(),
                parties.issueInvitation(secondRep, secondTeam, key(21),
                        new ExpectedVersionRequest(0L)).data().invitationCode());

        var attempts = runTogether(2, index -> parties.acceptInvitation(candidate,
                key(22 + index), new InvitationAcceptanceRequest(codes.get(index))));

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(attempts.stream().filter(attempt -> !attempt.succeeded())
                .map(Attempt::failure)).singleElement()
                .isInstanceOfSatisfying(com.miriyum.global.exception.ServiceException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(
                                com.miriyum.domain.reservation.exception.ReservationErrorCode
                                        .ACCOUNT_ACTIVE_WAITING_EXISTS));
        assertThat(memberships.findByConsumerAccountId(candidate)).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waiting_active_memberships "
                + "WHERE consumer_account_id=?", Long.class, candidate)).isOne();
    }

    @Test
    void twoAccountsCompetingForLastPartySlotProduceOneSuccess() throws Exception {
        long storeId = fixtureStore();
        long representative = createConsumer();
        long first = createConsumer();
        long second = createConsumer();
        long teamId = createTeam(storeId, representative, 2);
        List<String> codes = List.of(
                parties.issueInvitation(representative, teamId, key(30),
                        new ExpectedVersionRequest(0L)).data().invitationCode(),
                parties.issueInvitation(representative, teamId, key(31),
                        new ExpectedVersionRequest(0L)).data().invitationCode());
        List<Long> accounts = List.of(first, second);

        var attempts = runTogether(2, index -> parties.acceptInvitation(
                accounts.get(index), key(32 + index),
                new InvitationAcceptanceRequest(codes.get(index))));

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(memberships.countByWaitingTeamId(teamId)).isEqualTo(2L);
    }

    @Test
    void representativeAcceptanceRacingCancellationConvergesToOneOutcome() throws Exception {
        long storeId = fixtureStore();
        long representative = createConsumer();
        long member = createConsumer();
        long teamId = createTeam(storeId, representative, 2);
        String code = parties.issueInvitation(representative, teamId, key(40),
                new ExpectedVersionRequest(0L)).data().invitationCode();
        parties.acceptInvitation(member, key(41), new InvitationAcceptanceRequest(code));
        long memberId = memberships.findByConsumerAccountId(member).orElseThrow().getId();
        long offerId = Long.parseLong(parties.proposeTransfer(representative, teamId, key(42),
                new TransferProposalRequest(memberId, 1L)).data().offerId());

        var attempts = runTogether(2, index -> index == 0
                ? parties.acceptTransfer(member, teamId, offerId, key(43),
                        new ExpectedVersionRequest(1L))
                : consumers.cancel(representative, teamId, key(44),
                        new WaitingTeamTransitionRequest(1L)));

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        WaitingTeam stored = teams.findById(teamId).orElseThrow();
        if (stored.getStatus() == WaitingTeamStatus.WAITING) {
            assertThat(stored.getConsumerAccountId()).isEqualTo(member);
            assertThat(stored.getVersion()).isEqualTo(2L);
            assertThat(memberships.countByWaitingTeamId(teamId)).isEqualTo(2L);
        } else {
            assertThat(stored.getStatus()).isEqualTo(WaitingTeamStatus.CANCELLED);
            assertThat(stored.getConsumerAccountId()).isEqualTo(representative);
            assertThat(memberships.countByWaitingTeamId(teamId)).isZero();
        }
    }

    @Test
    void expiredRepresentativeTransferIsPersistedBeforeItsReplacement() {
        long storeId = fixtureStore();
        long representative = createConsumer();
        long member = createConsumer();
        long teamId = createTeam(storeId, representative, 2);
        String code = parties.issueInvitation(representative, teamId, key(50),
                new ExpectedVersionRequest(0L)).data().invitationCode();
        parties.acceptInvitation(member, key(51), new InvitationAcceptanceRequest(code));
        long memberId = memberships.findByConsumerAccountId(member).orElseThrow().getId();
        long expiredOfferId = Long.parseLong(parties.proposeTransfer(
                representative, teamId, key(52),
                new TransferProposalRequest(memberId, 1L)).data().offerId());
        jdbc.update("UPDATE waiting_representative_transfer_offers "
                + "SET proposed_at=DATE_SUB(NOW(6), INTERVAL 301 SECOND), "
                + "expires_at=DATE_SUB(NOW(6), INTERVAL 1 SECOND) "
                + "WHERE waiting_representative_transfer_offer_id=?", expiredOfferId);

        long replacementOfferId = Long.parseLong(parties.proposeTransfer(
                representative, teamId, key(53),
                new TransferProposalRequest(memberId, 1L)).data().offerId());

        assertThat(replacementOfferId).isNotEqualTo(expiredOfferId);
        assertThat(jdbc.queryForObject("SELECT status FROM "
                + "waiting_representative_transfer_offers "
                + "WHERE waiting_representative_transfer_offer_id=?", String.class,
                expiredOfferId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "
                + "waiting_representative_transfer_offers "
                + "WHERE waiting_team_id=? AND status='PROPOSED' AND active_team_key=?",
                Long.class, teamId, teamId)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waiting_party_audits "
                + "WHERE waiting_team_id=? AND event_type='REPRESENTATIVE_TRANSFER_EXPIRED'",
                Long.class, teamId)).isOne();
    }

    private long createTeam(long storeId, long accountId, int partySize) {
        return Long.parseLong(creation.create(storeId, accountId, BUSINESS_DATE, partySize,
                WaitingSource.REMOTE, key((int) (1000 + accountId))).data().waitingTeamId());
    }

    private long fixtureStore() {
        long operatorId = operators.saveAndFlush(StoreOperatorAccount.create(
                "party-race-" + UUID.randomUUID() + "@example.com", "hashed", "owner")).getId();
        long storeId = stores.saveAndFlush(Store.create(operatorId, registrationNumber(),
                BusinessType.CAFE, "Party Race Store", "", Region.SEOUL, "Seoul", "CAFE_BAKERY",
                Set.of(), true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        jdbc.update("INSERT INTO waiting_settings (store_id,enabled,reception_mode,"
                        + "advance_open_minutes,version,lock_version,created_at,updated_at) "
                        + "VALUES (?,TRUE,'MANUAL',60,1,0,NOW(6),NOW(6))", storeId);
        return storeId;
    }

    private long createConsumer() {
        String email = "party-consumer-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO consumer_accounts (email,password_hash,name,status,created_at,updated_at) "
                + "VALUES (?,'hashed','consumer','ACTIVE',NOW(6),NOW(6))", email);
        return jdbc.queryForObject("SELECT consumer_account_id FROM consumer_accounts WHERE email=?",
                Long.class, email);
    }

    private WaitingOperatingInterval openInterval(long storeId, LocalDate businessDate) {
        return new WaitingOperatingInterval(storeId, "party-race-" + storeId, 1L, businessDate,
                businessDate.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                clock.instant().plus(Duration.ofHours(1)), "Asia/Seoul");
    }

    private <T> List<Attempt<T>> runTogether(int count, ConcurrentWork<T> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            List<Future<Attempt<T>>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int participant = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try { return Attempt.success(work.run(participant)); }
                    catch (RuntimeException failure) { return Attempt.failure(failure); }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt<T>> results = new ArrayList<>();
            for (Future<Attempt<T>> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    private IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format("550e8400-e29b-41d4-a716-%012d", suffix));
    }

    private String registrationNumber() {
        return Long.toString(1000000000L
                + Math.abs(UUID.randomUUID().getMostSignificantBits() % 8999999999L));
    }

    @FunctionalInterface private interface ConcurrentWork<T> { T run(int index); }
    private record Attempt<T>(T result, RuntimeException failure) {
        static <T> Attempt<T> success(T result) { return new Attempt<>(result, null); }
        static <T> Attempt<T> failure(RuntimeException failure) { return new Attempt<>(null, failure); }
        boolean succeeded() { return failure == null; }
    }
}
