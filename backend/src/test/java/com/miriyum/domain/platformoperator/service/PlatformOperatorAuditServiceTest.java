package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditSearchRequest;
import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditCorrectionRequest;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuditEvent;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuditEventRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.util.Set;
import java.time.Instant;
import java.util.List;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlatformOperatorAuditServiceTest {

    @Test
    @DisplayName("SUPER_ADMIN도 AUDIT_READ가 없으면 조회가 거부되고 그 거부 시도 자체가 감사된다")
    void search_superAdminWithoutAuditReadDeniedAndAudited() {
        OperatorAuthorityReader authorityReader = mock(OperatorAuthorityReader.class);
        AdminCaseAssignmentVerifier assignmentVerifier = mock(AdminCaseAssignmentVerifier.class);
        PlatformOperatorAuditWriter writer = mock(PlatformOperatorAuditWriter.class);
        PlatformOperatorAuditEventRepository repository = mock(PlatformOperatorAuditEventRepository.class);
        PlatformOperatorAuditService service = new PlatformOperatorAuditService(
                authorityReader, assignmentVerifier, writer, repository,
                mock(HighRiskCommandGuard.class), mock(LastSuperAdminPolicy.class),
                mock(com.miriyum.global.idempotency.IdempotencyExecutor.class));
        PlatformOperatorPrincipal principal = new PlatformOperatorPrincipal(
                1L, "super@example.com", "session-1", 3L, 3L, false);
        when(authorityReader.requireCurrentAuthority(1L, 3L)).thenReturn(new OperatorAuthority(
                1L, 3L, Set.of(PlatformOperatorRole.SUPER_ADMIN),
                PlatformOperatorRole.SUPER_ADMIN.permissions()));

        assertThatThrownBy(() -> service.search(
                principal,
                new PlatformOperatorAuditSearchRequest(0, 20, null, null, null, null, null, null, null, null, null),
                "audit-review-1",
                1L,
                PlatformOperatorAuditReason.AUDIT_VERIFICATION,
                "correlation-read-1"))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));

        ArgumentCaptor<PlatformOperatorAuditWriter.ReadEvent> event =
                ArgumentCaptor.forClass(PlatformOperatorAuditWriter.ReadEvent.class);
        verify(writer).appendReadAttempt(event.capture());
        assertThat(event.getValue().outcome()).isEqualTo(PlatformOperatorAuditOutcome.DENIED);
        assertThat(event.getValue().reason()).isEqualTo(PlatformOperatorAuditReason.AUDIT_VERIFICATION);
    }

    @Test
    @DisplayName("AUDIT_READ와 AUDIT_REVIEW 배정이 있으면 통합 원장 결과를 반환하고 허용 조회를 감사한다")
    void search_leastPrivilegeGrantedReturnsProjectionAndAuditsSuccess() {
        OperatorAuthorityReader authorityReader = mock(OperatorAuthorityReader.class);
        AdminCaseAssignmentVerifier assignmentVerifier = mock(AdminCaseAssignmentVerifier.class);
        PlatformOperatorAuditWriter writer = mock(PlatformOperatorAuditWriter.class);
        PlatformOperatorAuditEventRepository repository = mock(PlatformOperatorAuditEventRepository.class);
        PlatformOperatorAuditService service = new PlatformOperatorAuditService(
                authorityReader, assignmentVerifier, writer, repository,
                mock(HighRiskCommandGuard.class), mock(LastSuperAdminPolicy.class),
                mock(com.miriyum.global.idempotency.IdempotencyExecutor.class));
        PlatformOperatorPrincipal principal = new PlatformOperatorPrincipal(
                7L, "auditor@example.com", "session-7", 2L, 2L, false);
        when(authorityReader.requireCurrentAuthority(7L, 2L)).thenReturn(new OperatorAuthority(
                7L, 2L, Set.of(PlatformOperatorRole.AUDIT_READER),
                Set.of(com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.AUDIT_READ)));
        PlatformOperatorAuditSearchRequest request = new PlatformOperatorAuditSearchRequest(
                0, 20, null, null, null, null, null, null, null, null, null);
        when(repository.search(request)).thenReturn(new PlatformOperatorAuditEventRepository.AuditPage(
                List.of(new PlatformOperatorAuditEventRepository.AuditRow(
                        "AUTH:11", "AUTH", "LOGIN", "SUCCESS", "7", 2L,
                        Set.of(), Set.of(), null, null, Set.of(), Set.of(), Set.of(), Set.of(),
                        "PLATFORM_OPERATOR_ACCOUNT", "7", "AUTHENTICATION_EVENT",
                        null, null, null, null, null, "auth-correlation", null,
                        Instant.parse("2026-08-14T00:00:00Z"))),
                1L));

        var result = service.search(principal, request, "audit-review-7", 1L,
                PlatformOperatorAuditReason.AUDIT_VERIFICATION, "correlation-read-7");

        assertThat(result.content()).extracting("eventKey").containsExactly("AUTH:11");
        ArgumentCaptor<PlatformOperatorAuditWriter.ReadEvent> event =
                ArgumentCaptor.forClass(PlatformOperatorAuditWriter.ReadEvent.class);
        verify(writer).appendReadAttempt(event.capture());
        assertThat(event.getValue().outcome()).isEqualTo(PlatformOperatorAuditOutcome.SUCCESS);
        verify(assignmentVerifier).verify(any());
    }

    @Test
    @DisplayName("같은 최초 원 사건에는 여러 보정 사건을 append할 수 있다")
    void correct_sameOriginalAppendsEveryCorrection() {
        OperatorAuthorityReader authorityReader = mock(OperatorAuthorityReader.class);
        AdminCaseAssignmentVerifier assignmentVerifier = mock(AdminCaseAssignmentVerifier.class);
        PlatformOperatorAuditWriter writer = mock(PlatformOperatorAuditWriter.class);
        PlatformOperatorAuditEventRepository repository = mock(PlatformOperatorAuditEventRepository.class);
        HighRiskCommandGuard guard = mock(HighRiskCommandGuard.class);
        LastSuperAdminPolicy singleton = mock(LastSuperAdminPolicy.class);
        com.miriyum.global.idempotency.IdempotencyExecutor idempotency =
                mock(com.miriyum.global.idempotency.IdempotencyExecutor.class);
        PlatformOperatorAuditService service = new PlatformOperatorAuditService(
                authorityReader, assignmentVerifier, writer, repository, guard, singleton, idempotency);
        PlatformOperatorPrincipal principal = new PlatformOperatorPrincipal(
                1L, "super@example.com", "session-1", 3L, 3L, false);
        when(repository.findProjected("AUTH:11")).thenReturn(java.util.Optional.of(
                new PlatformOperatorAuditEventRepository.AuditRow(
                        "AUTH:11", "AUTH", "LOGIN", "SUCCESS", "7", 2L,
                        Set.of(), Set.of(), null, null, Set.of(), Set.of(), Set.of(), Set.of(),
                        "PLATFORM_OPERATOR_ACCOUNT", "7", "AUTHENTICATION_EVENT",
                        null, null, null, null, null, "auth-correlation", null,
                        Instant.parse("2026-08-14T00:00:00Z"))));
        PlatformOperatorAuditEvent firstCorrection = mock(PlatformOperatorAuditEvent.class);
        PlatformOperatorAuditEvent secondCorrection = mock(PlatformOperatorAuditEvent.class);
        when(firstCorrection.getId()).thenReturn(21L);
        when(secondCorrection.getId()).thenReturn(22L);
        when(firstCorrection.getAction()).thenReturn(PlatformOperatorAuditAction.AUDIT_CORRECTION);
        when(secondCorrection.getAction()).thenReturn(PlatformOperatorAuditAction.AUDIT_CORRECTION);
        when(firstCorrection.getOutcome()).thenReturn(PlatformOperatorAuditOutcome.SUCCESS);
        when(secondCorrection.getOutcome()).thenReturn(PlatformOperatorAuditOutcome.SUCCESS);
        when(firstCorrection.getReason()).thenReturn(PlatformOperatorAuditReason.RECORD_CORRECTION);
        when(secondCorrection.getReason()).thenReturn(PlatformOperatorAuditReason.RECORD_CORRECTION);
        when(firstCorrection.getOccurredAt()).thenReturn(Instant.parse("2026-08-14T00:00:01Z"));
        when(secondCorrection.getOccurredAt()).thenReturn(Instant.parse("2026-08-14T00:00:02Z"));
        when(writer.appendCorrection(any())).thenReturn(firstCorrection, secondCorrection);
        when(guard.authorize(any())).thenReturn(new AdminAuditContext(
                1L, Set.of(PlatformOperatorRole.SUPER_ADMIN),
                PlatformOperatorRole.SUPER_ADMIN.permissions(), 3L,
                AdminCaseType.AUDIT_REVIEW, "audit-review-11", 1L,
                AdminCommandPurpose.AUDIT_CORRECTION, AdminTargetType.AUDIT_EVENT,
                "AUTH:11", "digest", "correlation-correction-11"));
        when(idempotency.execute(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<com.miriyum.global.idempotency.BusinessResult<Object>> work = invocation.getArgument(1);
            work.get();
            return new IdempotentOutcome(false, 201, "SUCCESS", null, null, null);
        });

        service.correct(
                new IdempotencyCommand("platform-operator", 1L, "AUDIT_CORRECTION",
                        "123e4567-e89b-12d3-a456-426614174000", "a".repeat(64)),
                principal,
                "AUTH:11",
                new PlatformOperatorAuditCorrectionRequest(
                        PlatformOperatorAuditReason.RECORD_CORRECTION,
                        PlatformOperatorAuditAction.LOGIN,
                        null, null, null, null),
                "audit-review-11", 1L, "approval", "correlation-correction-11");
        service.correct(
                new IdempotencyCommand("platform-operator", 1L, "AUDIT_CORRECTION",
                        "123e4567-e89b-12d3-a456-426614174001", "b".repeat(64)),
                principal,
                "AUTH:11",
                new PlatformOperatorAuditCorrectionRequest(
                        PlatformOperatorAuditReason.RECORD_CORRECTION,
                        null, PlatformOperatorAuditOutcome.DENIED,
                        null, null, null),
                "audit-review-11", 1L, "approval", "correlation-correction-12");

        verify(writer, times(2)).appendCorrection(any());
    }

    @Test
    @DisplayName("보정 사건 자체를 새 원 사건으로 지정하는 보정의 보정은 거부한다")
    void correct_correctionAsOriginalRejected() {
        PlatformOperatorAuditEventRepository repository = mock(PlatformOperatorAuditEventRepository.class);
        PlatformOperatorAuditWriter writer = mock(PlatformOperatorAuditWriter.class);
        HighRiskCommandGuard guard = mock(HighRiskCommandGuard.class);
        com.miriyum.global.idempotency.IdempotencyExecutor idempotency =
                mock(com.miriyum.global.idempotency.IdempotencyExecutor.class);
        PlatformOperatorAuditService service = new PlatformOperatorAuditService(
                mock(OperatorAuthorityReader.class), mock(AdminCaseAssignmentVerifier.class), writer,
                repository, guard, mock(LastSuperAdminPolicy.class), idempotency);
        PlatformOperatorPrincipal principal = new PlatformOperatorPrincipal(
                1L, "super@example.com", "session-1", 3L, 3L, false);
        when(repository.findProjected("ADMIN:21")).thenReturn(java.util.Optional.of(
                new PlatformOperatorAuditEventRepository.AuditRow(
                        "ADMIN:21", "ADMIN", "AUDIT_CORRECTION", "SUCCESS", "1", 3L,
                        Set.of("SUPER_ADMIN"), Set.of(), null, null,
                        Set.of(), Set.of(), Set.of(), Set.of(),
                        "AUDIT_EVENT", "AUTH:11", "RECORD_CORRECTION",
                        null, null, null, null, "SECURITY_RESPONSE",
                        "correlation-21", "AUTH:11", Instant.parse("2026-08-14T00:00:01Z"))));
        when(guard.authorize(any())).thenReturn(new AdminAuditContext(
                1L, Set.of(PlatformOperatorRole.SUPER_ADMIN),
                PlatformOperatorRole.SUPER_ADMIN.permissions(), 3L,
                AdminCaseType.AUDIT_REVIEW, "audit-review-21", 1L,
                AdminCommandPurpose.AUDIT_CORRECTION, AdminTargetType.AUDIT_EVENT,
                "ADMIN:21", "digest", "correlation-correction-21"));
        when(idempotency.execute(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<com.miriyum.global.idempotency.BusinessResult<Object>> work = invocation.getArgument(1);
            work.get();
            return new IdempotentOutcome(false, 201, "SUCCESS", null, null, null);
        });

        assertThatThrownBy(() -> service.correct(
                new IdempotencyCommand("platform-operator", 1L, "AUDIT_CORRECTION",
                        "123e4567-e89b-12d3-a456-426614174002", "c".repeat(64)),
                principal, "ADMIN:21",
                new PlatformOperatorAuditCorrectionRequest(
                        PlatformOperatorAuditReason.RECORD_CORRECTION,
                        null, PlatformOperatorAuditOutcome.DENIED, null, null, null),
                "audit-review-21", 1L, "approval", "correlation-correction-21"))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.AUDIT_CORRECTION_CONFLICT));
        verify(writer, org.mockito.Mockito.never()).appendCorrection(any());
    }
}
