package com.miriyum.domain.payment.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.payment.controller.consumer.PaymentController;
import com.miriyum.domain.payment.controller.publicapi.PortOneWebhookController;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.payment.service.PaymentWebhookService;
import com.miriyum.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = {PaymentController.class, PortOneWebhookController.class},
        properties = "miriyum.payment.enabled=false"
)
@Import({PaymentSecurityConfig.class, SecurityConfig.class})
class PaymentSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private PaymentWebhookService webhookService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private RateLimiter rateLimiter;

    @Test
    @DisplayName("Payment 기능을 끄면 Consumer와 Webhook HTTP 경로를 등록하지 않는다")
    void doesNotExposePaymentEndpointsWhenDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/consumers/payments"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/consumers/payments/900000000000000001"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(
                        "/api/v1/consumers/payments/900000000000000001/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"portOnePaymentId":"payment-reservation-900000000000000001"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/payments/webhooks/portone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
