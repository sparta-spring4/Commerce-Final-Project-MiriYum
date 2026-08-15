package com.miriyum.domain.platformoperator.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformOperatorAuditEventTest {

    @Test
    @DisplayName("관리 감사 사건은 구조화된 행위·사유와 변경 전후 권한만 보존한다")
    void createManagementEvent_preservesTypedSnapshots() {
        PlatformOperatorAuditEvent event = PlatformOperatorAuditEvent.create(
                1L,
                3L,
                Set.of(PlatformOperatorRole.SUPER_ADMIN),
                Set.of(PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE),
                PlatformOperatorAuditAction.AUTHORITY_REPLACED,
                PlatformOperatorAuditOutcome.SUCCESS,
                PlatformOperatorAuditReason.RESPONSIBILITY_CHANGE,
                "PLATFORM_OPERATOR_ACCOUNT",
                "7",
                AdminCaseType.OPERATOR_MANAGEMENT,
                "operator-management-7",
                2L,
                "123e4567-e89b-12d3-a456-426614174000",
                PlatformOperatorAccountStatus.ACTIVE,
                PlatformOperatorAccountStatus.ACTIVE,
                Set.of(PlatformOperatorRole.ONBOARDING_REVIEWER),
                Set.of(PlatformOperatorRole.AUDIT_READER),
                Set.of(PlatformOperatorPermission.ONBOARDING_REVIEW),
                Set.of(PlatformOperatorPermission.AUDIT_READ),
                "correlation-1",
                Instant.parse("2026-08-14T00:00:00Z"));

        assertThat(event.getActorPlatformOperatorAccountId()).isEqualTo(1L);
        assertThat(event.getActorRoles()).containsExactly(PlatformOperatorRole.SUPER_ADMIN);
        assertThat(event.getActorPermissions())
                .containsExactly(PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE);
        assertThat(event.getAction()).isEqualTo(PlatformOperatorAuditAction.AUTHORITY_REPLACED);
        assertThat(event.getBeforeRoles()).containsExactly(PlatformOperatorRole.ONBOARDING_REVIEWER);
        assertThat(event.getAfterRoles()).containsExactly(PlatformOperatorRole.AUDIT_READER);
        assertThat(event.getOriginalEventId()).isNull();
    }

    @Test
    @DisplayName("감사 엔티티에는 비밀번호·토큰·세션·승인 원문·임의 payload 저장 필드가 없다")
    void persistentShape_hasNoSensitiveSecretOrArbitraryPayloadField() {
        Set<String> fieldNames = Arrays.stream(PlatformOperatorAuditEvent.class.getDeclaredFields())
                .map(Field::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(fieldNames).noneMatch(name -> name.contains("password")
                || name.contains("token")
                || name.contains("session")
                || name.contains("approvaldigest")
                || name.contains("approvalraw")
                || name.contains("payload")
                || name.contains("header"));
    }

    @Test
    @DisplayName("보정 사건은 원 사건을 바꾸지 않고 원 ID와 구조화된 보정값을 연결한다")
    void createCorrection_linksOriginalWithoutMutationPayload() {
        PlatformOperatorAuditEvent correction = PlatformOperatorAuditEvent.createCorrection(
                1L,
                3L,
                Set.of(PlatformOperatorRole.SUPER_ADMIN),
                Set.of(PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE),
                "AUTH:41",
                PlatformOperatorAuditAction.LOGIN,
                PlatformOperatorAuditOutcome.DENIED,
                "PLATFORM_OPERATOR_ACCOUNT",
                "7",
                PlatformOperatorAuditReason.SECURITY_RESPONSE,
                AdminCaseType.AUDIT_REVIEW,
                "audit-review-41",
                1L,
                "123e4567-e89b-12d3-a456-426614174000",
                "correlation-2",
                Instant.parse("2026-08-14T00:01:00Z"));

        assertThat(correction.getAction()).isEqualTo(PlatformOperatorAuditAction.AUDIT_CORRECTION);
        assertThat(correction.getReason()).isEqualTo(PlatformOperatorAuditReason.RECORD_CORRECTION);
        assertThat(correction.getOriginalEventId()).isEqualTo(41L);
        assertThat(correction.getOriginalEventSource()).isEqualTo("AUTH");
        assertThat(correction.getCorrectedAction()).isEqualTo(PlatformOperatorAuditAction.LOGIN);
        assertThat(correction.getCorrectedOutcome()).isEqualTo(PlatformOperatorAuditOutcome.DENIED);
        assertThat(correction.getActorRoles()).containsExactly(PlatformOperatorRole.SUPER_ADMIN);
    }
}
