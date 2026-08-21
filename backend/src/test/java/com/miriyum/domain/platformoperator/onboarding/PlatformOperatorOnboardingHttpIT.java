package com.miriyum.domain.platformoperator.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.platformoperator.onboarding.controller.PlatformOperatorOnboardingReviewController;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

@Tag("integration")
@Tag("integration-shard-a")
class PlatformOperatorOnboardingHttpIT {

    @Test
    void reviewHttpBoundaryUsesOpaqueCaseIdAndRawEvidenceResponse() {
        var evidence = Arrays.stream(PlatformOperatorOnboardingReviewController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("readEvidence"))
                .findFirst().orElseThrow();
        assertThat(evidence.getAnnotation(GetMapping.class).value())
                .containsExactly("/{caseId}/evidence");
        assertThat(evidence.getReturnType()).isEqualTo(org.springframework.http.ResponseEntity.class);
        assertThat(Arrays.stream(evidence.getParameters())
                .flatMap(parameter -> Arrays.stream(parameter.getAnnotations())))
                .noneMatch(annotation -> annotation.annotationType().getSimpleName().equals("RequestBody"));
    }
}
