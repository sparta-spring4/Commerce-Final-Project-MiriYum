package com.miriyum.domain.platformoperator.membersupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberAppealOutcome;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorPermissionGrant;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentService;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.service.membersupport.MemberAppealService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportAuditWriter;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.platformoperator.session.PlatformOperatorSessionStore;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.platform-operator.enabled=true",
        "miriyum.platform-operator.reauthentication-fingerprint-secret=test-only-fingerprint-secret-at-least-32-characters",
        "miriyum.platform-operator.temporary-password.validity=PT10M",
        "miriyum.platform-operator.temporary-password.max-failures=3",
        "miriyum.member-support.enabled=true",
        "miriyum.member-support.dev-stub-enabled=true",
        "miriyum.member-support.proof-digest-secret=test-only-member-proof-secret",
        "miriyum.member-support.pii-encryption-active-key-version=1",
        "miriyum.member-support.pii-encryption-active-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "miriyum.rate-limit.login.max-requests=100",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.menu.schedule.enabled=false"
})
@AutoConfigureMockMvc
class PlatformOperatorMemberSupportHttpIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");
    @Container static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired PlatformOperatorAccountRepository operators;
    @Autowired PlatformOperatorAuthEventRepository authEvents;
    @Autowired PlatformOperatorPermissionGrantRepository permissionGrants;
    @Autowired PlatformOperatorRoleGrantRepository roleGrants;
    @Autowired ConsumerAccountRepository consumers;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MemberSanctionRepository sanctions;
    @Autowired MemberSupportCaseRepository supportCases;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberAppealService appeals;
    @Autowired PlatformOperatorSessionStore sessions;
    @MockitoBean HighRiskCommandGuard highRiskCommands;
    @MockitoBean AdminCaseAssignmentService assignments;
    @MockitoBean MemberSupportAuditWriter memberSupportAudits;

    @BeforeEach
    void clean() {
        jdbc.update("""
                DELETE FROM member_sanctions
                 WHERE member_support_case_id IN (
                     SELECT member_support_case_id FROM member_support_cases
                      WHERE source_sanction_id IS NOT NULL)
                """);
        jdbc.update("DELETE FROM member_support_cases WHERE source_sanction_id IS NOT NULL");
        jdbc.update("DELETE FROM member_sanctions");
        jdbc.update("DELETE FROM member_support_cases");
        authEvents.deleteAll();
        permissionGrants.deleteAll();
        roleGrants.deleteAll();
        operators.deleteAll();
        consumers.deleteAll();
    }

    @Test
    void permissionlessOperatorCannotUseMemberIdToProbeAnotherAccountType() throws Exception {
        PlatformOperatorAccount operator = createOperator();
        String accessToken = activateAndLogin(operator.getEmail());

        mvc.perform(get("/api/v1/platform-operators/members/STORE_OPERATOR/9223372036854775807")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_001"));
    }

    @Test
    void authorizedOperatorReceivesOnlyTheMinimalMemberProjection() throws Exception {
        ConsumerAccount consumer = consumers.saveAndFlush(ConsumerAccount.createWithContact(
                "private@example.com", "password-hash", "private-name", "+821012345678", "contact-ref"));
        PlatformOperatorAccount operator = createOperator();
        roleGrants.saveAndFlush(PlatformOperatorRoleGrant.create(
                operator.getId(), PlatformOperatorRole.MEMBER_SUPPORT_OPERATOR, Instant.now()));
        String accessToken = activateAndLogin(operator.getEmail());

        mvc.perform(get("/api/v1/platform-operators/members")
                        .param("accountType", "CONSUMER")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].accountType").value("CONSUMER"))
                .andExpect(jsonPath("$.data.content[0].accountId").value(consumer.getId().toString()))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.page.totalPages").value(1))
                .andExpect(jsonPath("$.data.page.hasNext").value(false))
                .andExpect(jsonPath("$.data.totalElements").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].phone").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].password").doesNotExist());
    }

    @Test
    void statusFilterUsesMysqlPaginationAndReturnsAnAccurateTotal() throws Exception {
        consumers.saveAndFlush(ConsumerAccount.createWithContact(
                "active@example.com", "password-hash", "active", "+821011111111", "active-ref"));
        ConsumerAccount suspended = ConsumerAccount.createWithContact(
                "suspended@example.com", "password-hash", "suspended", "+821022222222", "suspended-ref");
        suspended.applySupportSuspension();
        suspended = consumers.saveAndFlush(suspended);
        PlatformOperatorAccount operator = createOperator();
        roleGrants.saveAndFlush(PlatformOperatorRoleGrant.create(
                operator.getId(), PlatformOperatorRole.MEMBER_SUPPORT_OPERATOR, Instant.now()));
        String accessToken = activateAndLogin(operator.getEmail());

        mvc.perform(get("/api/v1/platform-operators/members")
                        .param("accountType", "CONSUMER")
                        .param("status", "TEMPORARILY_SUSPENDED")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].accountId").value(suspended.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].status").value("TEMPORARILY_SUSPENDED"));
    }

    @Test
    void activeFeatureSanctionIsProjectedAndFilteredFromMysqlLedger() throws Exception {
        ConsumerAccount consumer = consumers.saveAndFlush(ConsumerAccount.createWithContact(
                "feature@example.com", "password-hash", "feature", "+821033333333", "feature-ref"));
        PlatformOperatorAccount operator = createOperator();
        roleGrants.saveAndFlush(PlatformOperatorRoleGrant.create(
                operator.getId(), PlatformOperatorRole.MEMBER_SUPPORT_OPERATOR, Instant.now()));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MemberSupportCase supportCase = supportCases.saveAndFlush(MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, consumer.getId(), 0, "ABUSE", now));
        sanctions.saveAndFlush(MemberSanction.propose(supportCase, MemberSanctionLevel.FEATURE_RESTRICTION,
                Set.of(RestrictedFeature.RESERVATION), "ABUSE", "v1", operator.getId(), now));
        String accessToken = activateAndLogin(operator.getEmail());

        mvc.perform(get("/api/v1/platform-operators/members")
                        .param("accountType", "CONSUMER")
                        .param("status", "FEATURE_RESTRICTED")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("FEATURE_RESTRICTED"))
                .andExpect(jsonPath("$.data.content[0].activeSanctions[0].level")
                        .value("FEATURE_RESTRICTION"))
                .andExpect(jsonPath("$.data.content[0].activeSanctions[0].restrictedFeatures[0]")
                        .value("RESERVATION"))
                .andExpect(jsonPath("$.data.content[0].activeSanctions[0].endsAt")
                        .value(org.hamcrest.Matchers.endsWith("Z")));
    }

    @Test
    void supportCaseListMatchesThePublicOpenApiResponseContract() throws Exception {
        ConsumerAccount consumer = consumers.saveAndFlush(ConsumerAccount.createWithContact(
                "case@example.com", "password-hash", "case", "+821055555555", "case-ref"));
        LocalDateTime submittedAt = LocalDateTime.of(2026, 8, 16, 4, 5, 6);
        supportCases.saveAndFlush(MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, consumer.getId(), consumer.getSupportVersion(), "ABUSE", submittedAt));
        PlatformOperatorAccount operator = createOperator();
        roleGrants.saveAndFlush(PlatformOperatorRoleGrant.create(
                operator.getId(), PlatformOperatorRole.MEMBER_SUPPORT_OPERATOR, Instant.now()));
        String accessToken = activateAndLogin(operator.getEmail());

        mvc.perform(get("/api/v1/platform-operators/member-support-cases")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].accountId").value(consumer.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].submittedAt").value("2026-08-16T04:05:06Z"))
                .andExpect(jsonPath("$.data.content[0].targetSupportVersion").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].decisionCode").doesNotExist())
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.page.totalPages").value(1))
                .andExpect(jsonPath("$.data.page.hasNext").value(false))
                .andExpect(jsonPath("$.data.totalElements").doesNotExist());
    }

    @Test
    void pendingAdditionalApprovalsUseTheMysqlLedgerAndStablePagination() throws Exception {
        PlatformOperatorAccount approver = createOperator("approval-list@example.com");
        PlatformOperatorAccount proposer = createOperator("proposal-source@example.com");
        roleGrants.saveAndFlush(PlatformOperatorRoleGrant.create(
                approver.getId(), PlatformOperatorRole.SUPER_ADMIN, Instant.now()));
        String accessToken = activateAndLogin(approver.getEmail());
        LocalDateTime tiedAt = LocalDateTime.of(2026, 8, 19, 4, 5, 6);
        MemberSanction oldest = pendingSanction(101L, proposer.getId(), tiedAt.minusDays(1), "OLDEST");
        MemberSanction tiedFirst = pendingSanction(102L, proposer.getId(), tiedAt, "TIED_FIRST");
        MemberSanction tiedSecond = pendingSanction(103L, proposer.getId(), tiedAt, "TIED_SECOND");
        pendingSanction(104L, approver.getId(), tiedAt.plusDays(1), "OWN_PROPOSAL");
        appliedSanction(105L, proposer.getId(), tiedAt.plusDays(2));

        var firstPage = mvc.perform(get(
                        "/api/v1/platform-operators/member-sanctions/pending-additional-approvals")
                        .param("page", "0").param("size", "2")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(2))
                .andExpect(jsonPath("$.data.page.totalElements").value(3))
                .andExpect(jsonPath("$.data.page.totalPages").value(2))
                .andExpect(jsonPath("$.data.page.hasNext").value(true))
                .andExpect(jsonPath("$.data.content[0].sanctionId").value(tiedSecond.getPublicId()))
                .andExpect(jsonPath("$.data.content[1].sanctionId").value(tiedFirst.getPublicId()))
                .andExpect(jsonPath("$.data.content[0].version")
                        .value(tiedSecond.getSupportCase().getRowVersion()))
                .andReturn();

        java.util.Map<String, Object> item = JsonPath.read(
                firstPage.getResponse().getContentAsString(), "$.data.content[0]");
        assertThat(item).containsOnlyKeys(
                "sanctionId", "version", "accountType", "accountId",
                "reasonCode", "policyVersion", "proposedAt");
        assertThat(item).doesNotContainKeys(
                "proposedByOperatorId", "email", "phone", "level", "status", "restrictedFeatures");

        mvc.perform(get("/api/v1/platform-operators/member-sanctions/pending-additional-approvals")
                        .param("page", "1").param("size", "2")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].sanctionId").value(oldest.getPublicId()))
                .andExpect(jsonPath("$.data.page.hasNext").value(false));
    }

    @Test
    void pendingAdditionalApprovalsRequireBothPermissionAndSuperAdminRole() throws Exception {
        PlatformOperatorAccount permissionless = createOperator("permissionless-approval@example.com");
        String permissionlessToken = activateAndLogin(permissionless.getEmail());

        mvc.perform(get("/api/v1/platform-operators/member-sanctions/pending-additional-approvals")
                        .header("Authorization", "Bearer " + permissionlessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_001"));

        PlatformOperatorAccount directPermission = createOperator("direct-approval@example.com");
        permissionGrants.saveAndFlush(PlatformOperatorPermissionGrant.create(
                directPermission.getId(),
                PlatformOperatorPermission.ACCOUNT_PERMANENT_SANCTION_APPROVE, Instant.now()));
        String directPermissionToken = activateAndLogin(directPermission.getEmail());

        mvc.perform(get("/api/v1/platform-operators/member-sanctions/pending-additional-approvals")
                        .header("Authorization", "Bearer " + directPermissionToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_001"));
    }

    @Test
    void pendingAdditionalApprovalsRejectStaleAuthority() throws Exception {
        PlatformOperatorAccount stale = createOperator("stale-approval@example.com");
        roleGrants.saveAndFlush(PlatformOperatorRoleGrant.create(
                stale.getId(), PlatformOperatorRole.SUPER_ADMIN, Instant.now()));
        String staleToken = activateAndLogin(stale.getEmail());
        stale = operators.findById(stale.getId()).orElseThrow();
        stale.advanceAuthorityVersion();
        operators.saveAndFlush(stale);

        mvc.perform(get("/api/v1/platform-operators/member-sanctions/pending-additional-approvals")
                        .header("Authorization", "Bearer " + staleToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_015"));
    }

    @Test
    void pendingAdditionalApprovalsRejectRevokedCentralSession() throws Exception {
        PlatformOperatorAccount revoked = createOperator("revoked-approval@example.com");
        roleGrants.saveAndFlush(PlatformOperatorRoleGrant.create(
                revoked.getId(), PlatformOperatorRole.SUPER_ADMIN, Instant.now()));
        String revokedToken = activateAndLogin(revoked.getEmail());
        sessions.revokeAll(revoked.getId());

        mvc.perform(get("/api/v1/platform-operators/member-sanctions/pending-additional-approvals")
                        .header("Authorization", "Bearer " + revokedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_015"));
    }

    @Test
    void pendingAdditionalApprovalsRejectInvalidPageBoundaries() throws Exception {
        PlatformOperatorAccount approver = createOperator("approval-page@example.com");
        roleGrants.saveAndFlush(PlatformOperatorRoleGrant.create(
                approver.getId(), PlatformOperatorRole.SUPER_ADMIN, Instant.now()));
        String accessToken = activateAndLogin(approver.getEmail());

        for (String[] query : java.util.List.of(
                new String[]{"-1", "20"}, new String[]{"0", "0"}, new String[]{"0", "101"})) {
            mvc.perform(get("/api/v1/platform-operators/member-sanctions/pending-additional-approvals")
                            .param("page", query[0]).param("size", query[1])
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void cancellingOneSuspensionKeepsMysqlAccountSuspendedWhileAnotherRemainsActive() {
        ConsumerAccount consumer = ConsumerAccount.createWithContact(
                "overlap@example.com", "password-hash", "overlap", "+821044444444", "overlap-ref");
        consumer.applySupportSuspension();
        consumer = consumers.saveAndFlush(consumer);
        PlatformOperatorAccount operator = createOperator();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MemberSupportCase temporaryCase = terminalEnforcement(consumer.getId(), now, "TEMPORARY");
        MemberSanction temporary = sanctions.saveAndFlush(MemberSanction.propose(
                temporaryCase, MemberSanctionLevel.TEMPORARY_SUSPENSION, Set.of(),
                "ABUSE", "v1", operator.getId(), now));
        MemberSupportCase permanentCase = terminalEnforcement(consumer.getId(), now, "PERMANENT");
        MemberSanction permanent = MemberSanction.propose(
                permanentCase, MemberSanctionLevel.PERMANENT_SUSPENSION, Set.of(),
                "ABUSE", "v1", operator.getId(), now);
        permanent.approvePermanent(operator.getId(), now);
        sanctions.saveAndFlush(permanent);
        MemberSupportCase appeal = MemberSupportCase.appeal(
                MemberAccountType.CONSUMER, consumer.getId(), temporary.getId(),
                consumer.getSupportVersion(), now);
        appeal.assign();
        appeal = supportCases.saveAndFlush(appeal);
        when(highRiskCommands.authorize(any())).thenReturn(new AdminAuditContext(
                operator.getId(), Set.of(), Set.of(PlatformOperatorPermission.ACCOUNT_APPEAL_REVIEW), 1,
                AdminCaseType.MEMBER_SUPPORT, appeal.getPublicId(), appeal.getRowVersion(),
                AdminCommandPurpose.ACCOUNT_APPEAL_DECISION, AdminTargetType.CONSUMER_ACCOUNT,
                Long.toString(consumer.getId()), "fingerprint", "overlap-correlation"));
        PlatformOperatorPrincipal principal = new PlatformOperatorPrincipal(
                operator.getId(), operator.getEmail(), "session", 1, 1, false);

        appeals.decide(principal, appeal.getPublicId(), appeal.getRowVersion(), consumer.getSupportVersion(),
                MemberAppealOutcome.CANCEL, null, Set.of(), "approval",
                "overlap-correlation", "CANCELLED");

        ConsumerAccount reloaded = consumers.findById(consumer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ConsumerAccountStatus.SUSPENDED);
        assertThat(reloaded.getSupportVersion()).isEqualTo(2);
    }

    private MemberSupportCase terminalEnforcement(long accountId, LocalDateTime now, String decision) {
        MemberSupportCase supportCase = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, accountId, 0, "ABUSE", now);
        supportCase.assign();
        supportCase.decide(com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus.APPROVED,
                decision, now);
        return supportCases.saveAndFlush(supportCase);
    }

    private MemberSanction pendingSanction(
            long accountId, long proposerId, LocalDateTime proposedAt, String reasonCode) {
        MemberSupportCase supportCase = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, accountId, 0, reasonCode, proposedAt);
        supportCase.assign();
        supportCase.pendingAdditionalApproval();
        supportCase = supportCases.saveAndFlush(supportCase);
        return sanctions.saveAndFlush(MemberSanction.propose(
                supportCase, MemberSanctionLevel.PERMANENT_SUSPENSION, Set.of(),
                reasonCode, "policy-v3", proposerId, proposedAt));
    }

    private MemberSanction appliedSanction(long accountId, long proposerId, LocalDateTime proposedAt) {
        MemberSupportCase supportCase = supportCases.saveAndFlush(MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, accountId, 0, "APPLIED", proposedAt));
        return sanctions.saveAndFlush(MemberSanction.propose(
                supportCase, MemberSanctionLevel.TEMPORARY_SUSPENSION, Set.of(),
                "APPLIED", "policy-v3", proposerId, proposedAt));
    }

    private PlatformOperatorAccount createOperator() {
        return createOperator("operator@example.com");
    }

    private PlatformOperatorAccount createOperator(String email) {
        return operators.saveAndFlush(PlatformOperatorAccount.createTemporary(
                email, passwordEncoder.encode("Password1!"),
                "operator", Instant.now().plusSeconds(600)));
    }

    private String activateAndLogin(String email) throws Exception {
        var login = mvc.perform(post("/api/v1/platform-operators/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password1!\"}"))
                .andExpect(status().isOk()).andReturn();
        String limited = JsonPath.read(login.getResponse().getContentAsString(), "$.data.accessToken");
        var changed = mvc.perform(put("/api/v1/platform-operators/auth/initial-password")
                        .header("Authorization", "Bearer " + limited)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Password1!","newPassword":"Changed2@",
                                 "newPasswordConfirm":"Changed2@"}
                                """))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(changed.getResponse().getContentAsString(), "$.data.accessToken");
    }
}
