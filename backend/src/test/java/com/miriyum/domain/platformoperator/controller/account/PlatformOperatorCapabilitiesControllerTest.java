package com.miriyum.domain.platformoperator.controller.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.platformoperator.dto.authorization.PlatformOperatorCapabilitiesData;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.service.PlatformOperatorCapabilitiesService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class PlatformOperatorCapabilitiesControllerTest {
    private PlatformOperatorCapabilitiesService service;
    private PlatformOperatorPrincipal principal;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PlatformOperatorCapabilitiesService.class);
        principal = new PlatformOperatorPrincipal(1L, "secret@example.com", "session-secret", 3L, 4L, false);
        mvc = MockMvcBuilders.standaloneSetup(new PlatformOperatorCapabilitiesController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PrincipalResolver())
                .build();
    }

    @Test
    void currentResponseContainsOnlyCentralCapabilities() throws Exception {
        when(service.current(principal)).thenReturn(new PlatformOperatorCapabilitiesData(
                3L,
                List.of(PlatformOperatorRole.SUPER_ADMIN),
                List.of(PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE)));

        mvc.perform(get("/api/v1/platform-operators/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(3)))
                .andExpect(jsonPath("$.data.authorityVersion").value(3))
                .andExpect(jsonPath("$.data.roles[0]").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.permissions[0]").value("OPERATOR_AUTHORITY_MANAGE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret@example.com"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("session-secret"))));
    }

    @Test
    void authorityStoreFailureReturnsServiceUnavailableContract() throws Exception {
        OperatorAuthorityReader authorities = mock(OperatorAuthorityReader.class);
        when(authorities.requireCurrentAuthority(1L, 3L))
                .thenThrow(new DataAccessResourceFailureException("authority store unavailable"));
        MockMvc actualMvc = MockMvcBuilders
                .standaloneSetup(new PlatformOperatorCapabilitiesController(
                        new PlatformOperatorCapabilitiesService(authorities)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PrincipalResolver())
                .build();

        actualMvc.perform(get("/api/v1/platform-operators/me"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_012"));
    }

    @Test
    void featureFlagOffDoesNotExposeCapabilitiesController() {
        new ApplicationContextRunner()
                .withPropertyValues("miriyum.platform-operator.enabled=false")
                .withBean(PlatformOperatorCapabilitiesService.class, () -> service)
                .withUserConfiguration(PlatformOperatorCapabilitiesController.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PlatformOperatorCapabilitiesController.class));
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
