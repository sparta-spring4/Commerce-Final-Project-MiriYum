package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAuthorityReplaceRequest;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.platformoperator.session.PlatformOperatorSessionManager;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
        "miriyum.platform-operator.reauthentication-fingerprint-secret=test-only-reauthentication-fingerprint-secret",
        "miriyum.platform-operator.temporary-password.validity=PT10M",
        "miriyum.platform-operator.temporary-password.max-failures=3",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.menu.schedule.enabled=false"
})
class PlatformOperatorManagementSessionFailureIT {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40")
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired PlatformOperatorManagementService management;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorRoleGrantRepository roles;
    @Autowired OperatorAuthorityReader authorityReader;
    @Autowired PasswordEncoder encoder;

    @MockitoBean PlatformOperatorSessionManager sessions;
    @MockitoBean HighRiskCommandGuard highRiskGuard;
    @MockitoBean LastSuperAdminPolicy singletonPolicy;

    @Test
    @DisplayName("세션 저장소 폐기 실패 뒤에도 MySQL 권한 버전은 커밋되어 구 principal을 거부한다")
    void sessionRevocationFailureKeepsCommittedAuthorityVersionAndRejectsStalePrincipal() {
        PlatformOperatorAccount superAdmin = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "session-failure-super@example.com", encoder.encode("Password1!"), "super",
                Instant.now().plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                superAdmin.getId(), PlatformOperatorRole.SUPER_ADMIN, Instant.now()));
        PlatformOperatorAccount target = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "session-failure-target@example.com", encoder.encode("Password1!"), "target",
                Instant.now().plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                target.getId(), PlatformOperatorRole.AUDIT_READER, Instant.now()));

        when(highRiskGuard.authorize(any())).thenReturn(new AdminAuditContext(
                superAdmin.getId(), Set.of(PlatformOperatorRole.SUPER_ADMIN),
                PlatformOperatorRole.SUPER_ADMIN.permissions(), 1L,
                AdminCaseType.OPERATOR_MANAGEMENT, "operator-management-session-failure", 1L,
                AdminCommandPurpose.OPERATOR_AUTHORITY_CHANGE,
                AdminTargetType.PLATFORM_OPERATOR_ACCOUNT, target.getId().toString(),
                "digest", "correlation-session-failure"));
        doThrow(new IllegalStateException("valkey unavailable"))
                .when(sessions).revokeAll(target.getId());

        assertThatThrownBy(() -> management.replaceAuthority(
                new IdempotencyCommand("platform-operator", superAdmin.getId(),
                        "OPERATOR_AUTHORITY_REPLACE", "123e4567-e89b-12d3-a456-426614174000",
                        "a".repeat(64)),
                new PlatformOperatorPrincipal(superAdmin.getId(), superAdmin.getEmail(),
                        "session-super", 1L, 1L, false),
                target.getId(),
                new PlatformOperatorAuthorityReplaceRequest(
                        Set.of(PlatformOperatorRole.OPERATIONS_MONITOR), Set.of(),
                        PlatformOperatorAuditReason.RESPONSIBILITY_CHANGE),
                "operator-management-session-failure", 1L, "approval",
                "correlation-session-failure"))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));

        assertThat(accounts.findById(target.getId()).orElseThrow().getAuthorityVersion()).isEqualTo(2L);
        assertThatThrownBy(() -> authorityReader.requireCurrentAuthority(target.getId(), 1L))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
    }
}
