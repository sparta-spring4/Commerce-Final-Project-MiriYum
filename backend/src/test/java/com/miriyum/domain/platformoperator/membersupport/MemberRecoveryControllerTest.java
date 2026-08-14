package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.platformoperator.controller.membersupport.MemberRecoveryController;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.ConsumerRecoveryVerificationRequest;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.OpaqueProof;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportCookieFactory;
import com.miriyum.domain.platformoperator.service.membersupport.MemberRecoveryService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportProperties;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportSubmissionService;
import com.miriyum.domain.platformoperator.service.membersupport.MockMemberIdentityVerificationService;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MemberRecoveryControllerTest {

    @Test
    void validAndMissingTargetsReturnSameAcceptedBodyAndCookieShape() {
        MockMemberIdentityVerificationService verifications = mock(MockMemberIdentityVerificationService.class);
        MemberSupportSubmissionService submissions = mock(MemberSupportSubmissionService.class);
        MemberSupportProperties properties = new MemberSupportProperties(
                true, "proof", Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(15), Duration.ofMinutes(30), true);
        MemberRecoveryController controller = new MemberRecoveryController(
                verifications, submissions, new MemberSupportCookieFactory(properties),
                mock(MemberRecoveryService.class));
        var validRequest = new ConsumerRecoveryVerificationRequest(
                "old@example.com", "+821012345678", "new@example.com");
        var missingRequest = new ConsumerRecoveryVerificationRequest(
                "missing@example.com", "+821099999999", "new@example.com");
        when(verifications.issueRecovery(MemberAccountType.CONSUMER, validRequest.toCommand()))
                .thenReturn(new OpaqueProof("a".repeat(43)));
        when(verifications.issueRecovery(MemberAccountType.CONSUMER, missingRequest.toCommand()))
                .thenReturn(new OpaqueProof("b".repeat(43)));

        var valid = controller.verifyConsumer(validRequest);
        var missing = controller.verifyConsumer(missingRequest);

        assertThat(valid.getStatusCode().value()).isEqualTo(202);
        assertThat(valid.getBody()).isEqualTo(missing.getBody());
        String validCookie = valid.getHeaders().getFirst("Set-Cookie");
        String missingCookie = missing.getHeaders().getFirst("Set-Cookie");
        assertThat(validCookie).hasSameSizeAs(missingCookie)
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/consumers");
    }
}
