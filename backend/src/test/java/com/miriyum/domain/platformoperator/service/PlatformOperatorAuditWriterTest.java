package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuditEvent;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlatformOperatorAuditWriterTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    @Test
    @DisplayName("관리 명령 감사에는 승인 원문·digest 대신 권한과 사건 참조만 기록한다")
    void appendManagement_omitsApprovalSecret() {
        PlatformOperatorAuditEventRepository repository = mock(PlatformOperatorAuditEventRepository.class);
        when(repository.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PlatformOperatorAuditWriter writer = new PlatformOperatorAuditWriter(
                repository, Clock.fixed(NOW, ZoneOffset.UTC));
        AdminAuditContext context = new AdminAuditContext(
                1L,
                Set.of(PlatformOperatorRole.SUPER_ADMIN),
                Set.of(PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE),
                4L,
                AdminCaseType.OPERATOR_MANAGEMENT,
                "operator-management-7",
                2L,
                AdminCommandPurpose.OPERATOR_AUTHORITY_CHANGE,
                AdminTargetType.PLATFORM_OPERATOR_ACCOUNT,
                "7",
                "sensitive-approval-digest-must-not-be-persisted",
                "correlation-1");

        writer.appendManagement(new PlatformOperatorAuditWriter.ManagementEvent(
                context,
                PlatformOperatorAuditAction.AUTHORITY_REPLACED,
                PlatformOperatorAuditOutcome.SUCCESS,
                PlatformOperatorAuditReason.RESPONSIBILITY_CHANGE,
                "123e4567-e89b-12d3-a456-426614174000",
                PlatformOperatorAccountStatus.ACTIVE,
                PlatformOperatorAccountStatus.ACTIVE,
                Set.of(PlatformOperatorRole.ONBOARDING_REVIEWER),
                Set.of(PlatformOperatorRole.AUDIT_READER),
                Set.of(PlatformOperatorPermission.ONBOARDING_REVIEW),
                Set.of(PlatformOperatorPermission.AUDIT_READ)));

        ArgumentCaptor<PlatformOperatorAuditEvent> event = ArgumentCaptor.forClass(PlatformOperatorAuditEvent.class);
        verify(repository).append(event.capture());
        assertThat(event.getValue().getActorAuthorityVersion()).isEqualTo(4L);
        assertThat(event.getValue().getActorRoles()).containsExactly(PlatformOperatorRole.SUPER_ADMIN);
        assertThat(event.getValue().getActorPermissions())
                .containsExactly(PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE);
        assertThat(event.getValue().getBeforeRoles())
                .containsExactly(PlatformOperatorRole.ONBOARDING_REVIEWER);
        assertThat(event.getValue().getCaseId()).isEqualTo("operator-management-7");
        assertThat(event.getValue().getCorrelationId()).isEqualTo("correlation-1");
        assertThat(event.getValue().toString())
                .doesNotContain("sensitive-approval-digest-must-not-be-persisted");
    }
}
