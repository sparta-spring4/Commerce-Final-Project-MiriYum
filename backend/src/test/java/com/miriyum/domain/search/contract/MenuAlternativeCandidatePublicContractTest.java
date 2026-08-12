package com.miriyum.domain.search.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.search.dto.contract.MenuAlternativeCandidateView;
import com.miriyum.domain.search.dto.contract.MenuAlternativeSourceView;
import com.miriyum.domain.search.service.MenuAlternativeCandidateQueryService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MenuAlternativeCandidatePublicContractTest {

    @Test
    void exposesOnlyImmutableSearchOwnedDtos() {
        assertThat(MenuAlternativeSourceView.class.isRecord()).isTrue();
        assertThat(MenuAlternativeCandidateView.class.isRecord()).isTrue();
        assertThat(Arrays.stream(MenuAlternativeCandidateQueryService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::toGenericString))
                .allMatch(signature -> !signature.contains(".entity.")
                        && !signature.contains("Repository")
                        && !signature.contains("jakarta.persistence"));
    }
}
