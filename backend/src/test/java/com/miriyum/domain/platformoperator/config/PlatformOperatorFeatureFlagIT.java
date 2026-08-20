package com.miriyum.domain.platformoperator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.controller.auth.PlatformOperatorAuthController;
import com.miriyum.domain.platformoperator.controller.account.PlatformOperatorCapabilitiesController;
import com.miriyum.domain.platformoperator.controller.membersupport.PlatformOperatorMemberSupportController;
import com.miriyum.domain.platformoperator.service.PlatformOperatorCapabilitiesService;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.platform-operator.enabled=false"
})
@AutoConfigureMockMvc
class PlatformOperatorFeatureFlagIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40")
            .withCommand("--log-bin-trust-function-creators=1");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void disabledFeatureExposesNeitherControllerNorOpenEndedNamespace() throws Exception {
        assertThat(Arrays.stream(PlatformOperatorMemberSupportController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class))
                .flatMap(method -> Arrays.stream(method.getAnnotation(
                        org.springframework.web.bind.annotation.GetMapping.class).value())))
                .contains("/member-sanctions/pending-additional-approvals");
        assertThat(context.getBeansOfType(PlatformOperatorAuthController.class)).isEmpty();
        assertThat(context.getBeansOfType(PlatformOperatorCapabilitiesController.class)).isEmpty();
        assertThat(context.getBeansOfType(PlatformOperatorMemberSupportController.class)).isEmpty();
        assertThat(context.getBeansOfType(PlatformOperatorCapabilitiesService.class)).isEmpty();
        mvc.perform(get("/api/v1/platform-operators/me"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/platform-operators/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\",\"password\":\"Password1!\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/platform-operators/future-business"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/platform-operators/member-sanctions/pending-additional-approvals"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/platform-operators/auth/accounts"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/platform-operators/auth/token-refreshes")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/platform-operators/auth/csrf-tokens/current"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/platform-operators/auth/sessions/current"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/platform-operators/auth/initial-password")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
