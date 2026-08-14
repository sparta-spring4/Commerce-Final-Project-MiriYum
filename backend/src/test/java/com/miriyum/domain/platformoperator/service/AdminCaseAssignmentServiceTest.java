package com.miriyum.domain.platformoperator.service;

import static com.miriyum.domain.platformoperator.enums.AdminCaseType.ONBOARDING_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.entity.AdminCaseAssignment;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.AdminCaseAssignmentRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.exception.CommonErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class AdminCaseAssignmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private AdminCaseAssignmentRepository assignments;
    private AdminCaseAssignmentService service;

    @BeforeEach
    void setUp() {
        assignments = mock(AdminCaseAssignmentRepository.class);
        service = new AdminCaseAssignmentService(assignments, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("현재 담당자·사건 version·만료가 모두 맞는 활성 배정만 승인한다")
    void verifiesExactActiveAssignment() {
        AdminCaseAssignmentRequest request = request(3L, 7L);
        when(assignments.findByCaseForUpdate(ONBOARDING_REVIEW, "case-1", 3L))
                .thenReturn(Optional.of(AdminCaseAssignment.assign(
                        ONBOARDING_REVIEW, "case-1", 3L, 7L, NOW.plusSeconds(60), NOW)));

        assertThatCode(() -> service.verify(request)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("사건 version이 다르면 존재 여부를 구분하지 않고 공통 거부한다")
    void rejectsStaleCaseVersion() {
        AdminCaseAssignmentRequest request = request(2L, 7L);
        when(assignments.findByCaseForUpdate(ONBOARDING_REVIEW, "case-1", 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
    }

    @Test
    @DisplayName("만료된 배정은 공통 거부한다")
    void rejectsExpiredAssignment() {
        AdminCaseAssignmentRequest request = request(3L, 7L);
        when(assignments.findByCaseForUpdate(ONBOARDING_REVIEW, "case-1", 3L))
                .thenReturn(Optional.of(AdminCaseAssignment.assign(
                        ONBOARDING_REVIEW, "case-1", 3L, 7L, NOW, NOW.minusSeconds(60))));

        assertThatThrownBy(() -> service.verify(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
    }

    @Test
    void assignsAnUnclaimedCase() {
        when(assignments.findByCaseForUpdate(ONBOARDING_REVIEW, "case-1", 3L))
                .thenReturn(Optional.empty());

        service.assign(new AdminCaseAssignmentCommand(
                ONBOARDING_REVIEW, "case-1", 3L, 7L, NOW.plusSeconds(60)));

        verify(assignments).saveAndFlush(org.mockito.ArgumentMatchers.argThat(assignment ->
                assignment.getPlatformOperatorAccountId() == 7L
                        && assignment.isActiveAt(NOW)));
    }

    @Test
    void closesOnlyTheExactCurrentAssignment() {
        AdminCaseAssignment assignment = AdminCaseAssignment.assign(
                ONBOARDING_REVIEW, "case-1", 3L, 7L, NOW.plusSeconds(60), NOW);
        when(assignments.findByCaseForUpdate(ONBOARDING_REVIEW, "case-1", 3L))
                .thenReturn(Optional.of(assignment));

        service.close(request(3L, 7L));

        assertThat(assignment.isActiveAt(NOW)).isFalse();
    }

    @Test
    void mapsConcurrentInitialAssignmentLockFailureToConflict() {
        when(assignments.findByCaseForUpdate(ONBOARDING_REVIEW, "case-1", 3L))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> service.assign(new AdminCaseAssignmentCommand(
                ONBOARDING_REVIEW, "case-1", 3L, 7L, NOW.plusSeconds(60))))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION));
    }

    private static AdminCaseAssignmentRequest request(long caseVersion, long operatorId) {
        return new AdminCaseAssignmentRequest(ONBOARDING_REVIEW, "case-1", caseVersion, operatorId);
    }
}
