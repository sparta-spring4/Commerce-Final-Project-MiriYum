package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportAssignmentService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportAuthorizationService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportProperties;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemberSupportAssignmentServiceTest {
    @Test
    void assignmentAdvancesCaseVersionAndBindsCentralAssignmentToIt() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
        MemberSupportCase supportCase = MemberSupportCase.recovery(
                MemberAccountType.CONSUMER, 41, 7, 0, LocalDateTime.now(clock));
        MemberSupportCaseRepository cases = mock(MemberSupportCaseRepository.class);
        when(cases.findByPublicIdForUpdate(supportCase.getPublicId())).thenReturn(Optional.of(supportCase));
        OperatorAuthorityReader authorities = mock(OperatorAuthorityReader.class);
        when(authorities.requireCurrentAuthority(9, 2)).thenReturn(new OperatorAuthority(
                9, 2, Set.of(), Set.of(PlatformOperatorPermission.MEMBER_RECOVERY)));
        AdminCaseAssignmentManager assignments = mock(AdminCaseAssignmentManager.class);
        MemberSupportProperties properties = new MemberSupportProperties(
                true, "proof", Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(15), Duration.ofMinutes(30), true);
        MemberSupportAssignmentService service = new MemberSupportAssignmentService(
                cases, assignments, new MemberSupportAuthorizationService(authorities), properties, clock);
        PlatformOperatorPrincipal principal = new PlatformOperatorPrincipal(
                9L, "admin@example.com", "session", 2, 3, false);

        service.assign(principal, supportCase.getPublicId());

        assertThat(supportCase.getStatus()).isEqualTo(MemberSupportCaseStatus.ASSIGNED);
        assertThat(supportCase.getRowVersion()).isEqualTo(2);
        ArgumentCaptor<AdminCaseAssignmentCommand> command = ArgumentCaptor.forClass(AdminCaseAssignmentCommand.class);
        verify(assignments).assign(command.capture());
        assertThat(command.getValue()).isEqualTo(new AdminCaseAssignmentCommand(
                AdminCaseType.MEMBER_SUPPORT, supportCase.getPublicId(), 2, 9,
                Instant.parse("2026-08-14T00:30:00Z")));
    }
}
