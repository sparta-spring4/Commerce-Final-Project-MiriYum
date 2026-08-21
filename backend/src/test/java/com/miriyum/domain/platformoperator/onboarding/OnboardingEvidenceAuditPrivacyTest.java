package com.miriyum.domain.platformoperator.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class OnboardingEvidenceAuditPrivacyTest {

    @Test
    void evidenceAttemptAuditIsIndependentAndHasOnlySafeFieldNames() throws Exception {
        Transactional transaction = PlatformOperatorAuditWriter.class
                .getMethod("appendOnboardingReadAttempt",
                        PlatformOperatorAuditWriter.OnboardingReadAttempt.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(Arrays.stream(PlatformOperatorAuditWriter.OnboardingReadAttempt.class
                        .getRecordComponents()).map(component -> component.getName()))
                .containsExactlyInAnyOrder(
                        "actorId", "authorityVersion", "roles", "permissions", "outcome",
                        "caseId", "caseVersion", "correlationId")
                .noneMatch(name -> SetHolder.SENSITIVE.contains(name));
    }

    private static final class SetHolder {
        private static final java.util.Set<String> SENSITIVE = java.util.Set.of(
                "objectKey", "fileId", "evidenceId", "businessRegistrationNumber",
                "representativeName", "approval", "approvalFingerprint");
    }
}
