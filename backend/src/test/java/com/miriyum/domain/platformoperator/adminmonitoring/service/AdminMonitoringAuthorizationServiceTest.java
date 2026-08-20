package com.miriyum.domain.platformoperator.adminmonitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentVerifier;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminMonitoringAuthorizationServiceTest {

    private static final PlatformOperatorPrincipal PRINCIPAL = new PlatformOperatorPrincipal(
            17L, "operator@example.com", "session", 3L, 2L, false);

    private OperatorAuthorityReader authorities;
    private AdminCaseAssignmentVerifier assignments;
    private AdminMonitoringAuthorizationService authorization;

    @BeforeEach
    void setUp() {
        authorities = mock(OperatorAuthorityReader.class);
        assignments = mock(AdminCaseAssignmentVerifier.class);
        authorization = new AdminMonitoringAuthorizationService(authorities, assignments);
    }

    @Test
    void requiresTheCurrentAuthorityVersionAndMonitoringReadPermission() {
        given(authorities.requireCurrentAuthority(17L, 3L)).willReturn(new OperatorAuthority(
                17L, 3L, Set.of(), Set.of(PlatformOperatorPermission.OPERATIONS_MONITOR_READ)));

        authorization.requireRead(PRINCIPAL);

        then(authorities).should().requireCurrentAuthority(17L, 3L);
    }

    @Test
    void rejectsAnOperatorWithoutMonitoringReadPermission() {
        given(authorities.requireCurrentAuthority(17L, 3L)).willReturn(
                new OperatorAuthority(17L, 3L, Set.of(), Set.of()));

        assertThatThrownBy(() -> authorization.requireRead(PRINCIPAL))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
    }

    @Test
    void bindsDetailAssignmentToMonitoringCaseIdVersionAndOperator() {
        given(authorities.requireCurrentAuthority(17L, 3L)).willReturn(new OperatorAuthority(
                17L, 3L, Set.of(), Set.of(PlatformOperatorPermission.OPERATIONS_MONITOR_READ)));

        authorization.requireDetail(PRINCIPAL, "reservation-hold:91", 4L);

        ArgumentCaptor<AdminCaseAssignmentRequest> request =
                ArgumentCaptor.forClass(AdminCaseAssignmentRequest.class);
        then(assignments).should().verify(request.capture());
        assertThat(request.getValue()).isEqualTo(new AdminCaseAssignmentRequest(
                AdminCaseType.OPERATIONS_MONITORING, "reservation-hold:91", 4L, 17L));
    }
}
