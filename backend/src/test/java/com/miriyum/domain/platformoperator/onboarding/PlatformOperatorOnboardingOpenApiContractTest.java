package com.miriyum.domain.platformoperator.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PlatformOperatorOnboardingOpenApiContractTest {

    private static final Path CONTRACT = Path.of(
            "..", "docs", "specs", "platform-operator-onboarding-review", "openapi.yaml");
    private static final Set<String> PATHS = Set.of(
            "/api/v1/platform-operators/onboarding-review-cases",
            "/api/v1/platform-operators/onboarding-review-cases/{caseId}",
            "/api/v1/platform-operators/onboarding-review-cases/{caseId}/assignments",
            "/api/v1/platform-operators/onboarding-review-cases/{caseId}/reassignments",
            "/api/v1/platform-operators/onboarding-review-cases/{caseId}/decisions",
            "/api/v1/platform-operators/onboarding-review-cases/{caseId}/evidence");

    @Test
    void exposesTheCompleteOnboardingReviewWorkflow() throws IOException {
        assertThat(paths().keySet()).containsExactlyInAnyOrderElementsOf(PATHS);
    }

    @Test
    void decisionsAllowOnlyTheThreeReviewActions() throws IOException {
        Map<String, Object> schemas = map(map(document().get("components")).get("schemas"));
        Map<String, Object> decisionType = map(schemas.get("OnboardingDecisionType"));

        assertThat(decisionType.get("enum"))
                .isEqualTo(java.util.List.of("APPROVE", "REJECT", "REQUEST_CHANGES"));
    }

    private static Map<String, Object> paths() throws IOException {
        return map(document().get("paths"));
    }

    private static Map<String, Object> document() throws IOException {
        try (InputStream input = Files.newInputStream(CONTRACT)) {
            return map(new Yaml().load(input));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
