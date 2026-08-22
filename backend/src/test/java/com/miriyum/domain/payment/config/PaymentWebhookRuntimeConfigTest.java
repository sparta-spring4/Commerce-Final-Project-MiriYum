package com.miriyum.domain.payment.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.payment.controller.publicapi.PortOneWebhookController;
import com.miriyum.domain.payment.service.PaymentWebhookService;
import com.miriyum.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = PortOneWebhookController.class,
        properties = "miriyum.payment.enabled=true"
)
@Import({PaymentSecurityConfig.class, SecurityConfig.class})
class PaymentWebhookRuntimeConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentWebhookService webhookService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private RateLimiter rateLimiter;

    @Test
    @DisplayName("결제를 켜도 Webhook 활성화 값이 없으면 공개 Webhook 경로를 등록하지 않는다")
    void doesNotExposeWebhookWhenWebhookRuntimeIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/payments/webhooks/portone"))
                .andExpect(status().isNotFound());
    }
}
