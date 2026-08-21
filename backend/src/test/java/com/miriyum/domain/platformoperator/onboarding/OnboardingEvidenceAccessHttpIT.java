package com.miriyum.domain.platformoperator.onboarding;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.platformoperator.controller.onboarding.PlatformOperatorOnboardingReviewController;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingEvidenceAccessService;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewCommandService;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewQueryService;
import com.miriyum.domain.store.evidence.dto.BusinessRegistrationEvidenceContent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("integration")
@Tag("integration-shard-a")
class OnboardingEvidenceAccessHttpIT {

    @Test
    void returnsRawPrivateBytesWithoutStorageIdentityOrCaching() throws Exception {
        String caseId = "550e8400-e29b-41d4-a716-446655440277";
        var evidence = org.mockito.Mockito.mock(OnboardingEvidenceAccessService.class);
        given(evidence.read(null, caseId, 2L, "approval", "correlation"))
                .willReturn(new BusinessRegistrationEvidenceContent(
                        "application/pdf", "%PDF-safe-private-content".getBytes()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformOperatorOnboardingReviewController(
                        org.mockito.Mockito.mock(OnboardingReviewQueryService.class),
                        org.mockito.Mockito.mock(OnboardingReviewCommandService.class), evidence))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        mvc.perform(get("/api/v1/platform-operators/onboarding-review-cases/{caseId}/evidence", caseId)
                        .queryParam("expectedCaseVersion", "2")
                        .header("X-Admin-Reauthentication", "approval")
                        .header("X-Correlation-Id", "correlation"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().bytes("%PDF-safe-private-content".getBytes()));
    }
}
