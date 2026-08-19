package com.miriyum.domain.platformoperator.controller.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.platformoperator.service.PlatformOperatorAccountQueryService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class PlatformOperatorAccountQueryControllerTest {
    private PlatformOperatorAccountQueryService service;
    private PlatformOperatorPrincipal principal;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PlatformOperatorAccountQueryService.class);
        principal = new PlatformOperatorPrincipal(1L, "secret@example.com", "session-secret", 3L, 4L, false);
        mvc = MockMvcBuilders.standaloneSetup(new PlatformOperatorAccountQueryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PrincipalResolver())
                .build();
    }

    @Test
    void invalidFiltersPageSizeAndSortAreRejectedBeforeServiceLookup() throws Exception {
        mvc.perform(get("/api/v1/platform-operators/accounts")
                        .queryParam("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        mvc.perform(get("/api/v1/platform-operators/accounts")
                        .queryParam("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        mvc.perform(get("/api/v1/platform-operators/accounts")
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        mvc.perform(get("/api/v1/platform-operators/accounts")
                        .queryParam("sort", "passwordHash,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        verifyNoInteractions(service);
    }

    @Test
    void nonPositiveOperatorIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/platform-operators/accounts/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        verifyNoInteractions(service);
    }

    @Test
    void featureFlagOffDoesNotExposeAccountReadController() {
        new ApplicationContextRunner()
                .withPropertyValues("miriyum.platform-operator.enabled=false")
                .withBean(PlatformOperatorAccountQueryService.class, () -> service)
                .withUserConfiguration(PlatformOperatorAccountQueryController.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PlatformOperatorAccountQueryController.class));
    }

    private final class PrincipalResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
            return principal;
        }
    }
}
