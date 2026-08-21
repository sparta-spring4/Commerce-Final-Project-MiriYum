package com.miriyum.domain.platformoperator.onboarding;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewQueryService;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingReviewWorkflow;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnboardingReviewQueryServiceTest {
    @Mock OperatorAuthorityReader authorities;
    @Mock StoreOnboardingReviewWorkflow workflow;

    @Test
    void detailRequiresCurrentOnboardingReviewPermission() {
        var principal = new PlatformOperatorPrincipal(91L, "op@example.com", "s", 2L, 1L, false);
        given(authorities.requireCurrentAuthority(91L, 2L)).willReturn(
                new OperatorAuthority(
                        91L, 2L, Set.of(), Set.of(PlatformOperatorPermission.ONBOARDING_REVIEW)));
        var service = new OnboardingReviewQueryService(authorities, workflow);

        service.detail(principal, "case-id");

        then(workflow).should().getReviewCase("case-id");
    }
}
