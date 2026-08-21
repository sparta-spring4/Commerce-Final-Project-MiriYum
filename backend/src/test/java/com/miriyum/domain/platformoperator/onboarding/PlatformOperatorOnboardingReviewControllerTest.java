package com.miriyum.domain.platformoperator.onboarding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.onboarding.controller.PlatformOperatorOnboardingReviewController;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewCommandService;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewQueryService;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingEvidenceAccessService;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlatformOperatorOnboardingReviewControllerTest {
    @Test
    void rejectsNonUuidPublicCaseIdBeforeQuery() {
        var controller = new PlatformOperatorOnboardingReviewController(
                Mockito.mock(OnboardingReviewQueryService.class),
                Mockito.mock(OnboardingReviewCommandService.class),
                Mockito.mock(OnboardingEvidenceAccessService.class));

        assertThatThrownBy(() -> controller.detail(null, "internal-sequence-41"))
                .isInstanceOf(ServiceException.class);
    }
}
