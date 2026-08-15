package com.miriyum.domain.platformoperator.membersupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-a")
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
        "miriyum.member-support.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
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
    @Autowired PlatformOperatorRoleGrantRepository roleGrants;
    @Autowired ConsumerAccountRepository consumers;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void clean() {
        authEvents.deleteAll();
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
                .andExpect(jsonPath("$.data.content[0].accountId").value(consumer.getId()))
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
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].accountId").value(suspended.getId()))
                .andExpect(jsonPath("$.data.content[0].status").value("TEMPORARILY_SUSPENDED"));
    }

    private PlatformOperatorAccount createOperator() {
        return operators.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "operator@example.com", passwordEncoder.encode("Password1!"),
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
