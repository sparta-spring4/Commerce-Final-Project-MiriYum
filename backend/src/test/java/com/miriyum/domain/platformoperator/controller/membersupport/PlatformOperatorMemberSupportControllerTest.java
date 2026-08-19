package com.miriyum.domain.platformoperator.controller.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.platformoperator.dto.membersupport.PlatformMemberSupportRequests.AdditionalApprovalRequest;
import com.miriyum.domain.platformoperator.dto.membersupport.PlatformMemberSupportRequests.SanctionRequest;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSanctionCommandService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSanctionService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportAssignmentService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportCaseQueryService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportDecisionService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportQueryService;
import com.miriyum.domain.platformoperator.service.membersupport.PendingMemberSanctionQueryService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PlatformOperatorMemberSupportControllerTest {
    private final MemberSanctionCommandService sanctionCommands = mock(MemberSanctionCommandService.class);
    private final MemberSanctionService sanctions = mock(MemberSanctionService.class);
    private final PlatformOperatorMemberSupportController controller =
            new PlatformOperatorMemberSupportController(
                    mock(MemberSupportQueryService.class),
                    mock(MemberSupportAssignmentService.class),
                    mock(MemberSupportDecisionService.class),
                    sanctionCommands,
                    sanctions,
                    mock(MemberSupportCaseQueryService.class),
                    mock(PendingMemberSanctionQueryService.class));

    @Test
    void sanctionResponseContainsTheCreatedSanctionEnvelopeData() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 12, 30);
        MemberSupportCase supportCase = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, 42L, 3L, "ABUSE_REPORT", now);
        MemberSanction sanction = MemberSanction.propose(
                supportCase, MemberSanctionLevel.WARNING, Set.of(),
                "ABUSE_REPORT", "SANCTION_POLICY_V1", 7L, now);
        when(sanctionCommands.createAndApply(
                any(), any(), anyLong(), anyLong(), any(), anySet(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sanction);

        var response = controller.sanction(
                new PlatformOperatorPrincipal(7L, "operator@example.com", "session", 1L, 1L, false),
                MemberAccountType.CONSUMER,
                42L,
                "3",
                "approval",
                "correlation",
                new SanctionRequest(
                        MemberSanctionLevel.WARNING,
                        "ABUSE_REPORT",
                        "SANCTION_POLICY_V1",
                        Set.of()));

        var data = new ObjectMapper().valueToTree(response.data());
        assertThat(data.path("sanctionId").asText()).isEqualTo(sanction.getPublicId());
        assertThat(data.path("accountType").asText()).isEqualTo("CONSUMER");
        assertThat(data.path("accountId").asText()).isEqualTo("42");
        assertThat(data.path("status").asText()).isEqualTo("APPLIED");
        assertThat(data.path("version").asLong()).isEqualTo(1L);
    }

    @Test
    void additionalApprovalResponseContainsTheUpdatedSanctionSnapshot() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 12, 30);
        MemberSupportCase supportCase = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, 42L, 3L, "ABUSE_REPORT", now);
        MemberSanction sanction = MemberSanction.propose(
                supportCase, MemberSanctionLevel.PERMANENT_SUSPENSION, Set.of(),
                "ABUSE_REPORT", "SANCTION_POLICY_V1", 7L, now);
        sanction.approvePermanent(8L, now.plusMinutes(1));
        when(sanctions.approvePermanent(
                any(), anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(sanction);

        var response = controller.approvePermanent(
                new PlatformOperatorPrincipal(8L, "super@example.com", "session", 1L, 1L, false),
                sanction.getPublicId(),
                "2",
                "approval",
                "correlation",
                new AdditionalApprovalRequest("APPROVE", "SECOND_REVIEW"));

        var data = new ObjectMapper().valueToTree(response.data());
        assertThat(data.path("sanctionId").asText()).isEqualTo(sanction.getPublicId());
        assertThat(data.path("status").asText()).isEqualTo("APPLIED");
        assertThat(data.path("version").asLong()).isEqualTo(2L);
        assertThat(data.path("appliedAt").asText()).isNotBlank();
    }
}
