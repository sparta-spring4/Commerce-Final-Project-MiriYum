package com.miriyum.domain.store.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.controller.storeoperator.StoreController;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;

@Tag("integration")
@Tag("integration-shard-a")
class StoreOnboardingHttpIT {

    @Test
    void storeCreationIsAlwaysMultipartWithMandatoryApplicationAndEvidenceParts()
            throws Exception {
        var method = StoreController.class.getMethod(
                "create", com.miriyum.domain.auth.jwt.AuthenticatedPrincipal.class,
                String.class, com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest.class,
                org.springframework.web.multipart.MultipartFile.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertThat(mapping.consumes()).containsExactly(MediaType.MULTIPART_FORM_DATA_VALUE);
        assertThat(Arrays.stream(method.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestPart.class))
                .filter(java.util.Objects::nonNull)
                .map(RequestPart::value))
                .containsExactly("application", "businessRegistrationEvidence");
        assertThat(Arrays.stream(method.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestPart.class))
                .filter(java.util.Objects::nonNull))
                .allMatch(RequestPart::required);
    }
}
