package com.miriyum.domain.payment.controller.publicapi;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.payment.config.PaymentSecurityConfig;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.service.PaymentWebhookService;
import com.miriyum.domain.payment.service.PaymentWebhookService.WebhookResult;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PortOneWebhookController.class)
@Import({PaymentSecurityConfig.class, GlobalExceptionHandler.class})
class PortOneWebhookControllerTest {

    private static final String RAW_BODY =
            "{\"type\":\"Transaction.Paid\",\"timestamp\":\"2026-08-11T01:00:00Z\",\"data\":{\"storeId\":\"store-1\",\"paymentId\":\"payment-reservation-900000000000000001\",\"transactionId\":\"transaction-1\"}}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentWebhookService webhookService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("raw body와 Standard Webhooks 헤더를 변형 없이 전달하고 200을 반환한다")
    void receivesVerifiedWebhook() throws Exception {
        given(webhookService.handle(
                RAW_BODY, "msg_1", "1786410000", "v1,signature"))
                .willReturn(WebhookResult.PROCESSED);

        mockMvc.perform(post("/api/v1/payments/webhooks/portone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("webhook-id", "msg_1")
                        .header("webhook-timestamp", "1786410000")
                        .header("webhook-signature", "v1,signature")
                        .content(RAW_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

        then(webhookService).should().handle(
                RAW_BODY, "msg_1", "1786410000", "v1,signature");
    }

    @Test
    @DisplayName("검증됐지만 PortOne 조회가 불명확한 사건은 202로 반환한다")
    void returnsAcceptedForReconciliation() throws Exception {
        given(webhookService.handle(
                RAW_BODY, "msg_1", "1786410000", "v1,signature"))
                .willReturn(WebhookResult.RECONCILIATION_REQUIRED);

        mockMvc.perform(post("/api/v1/payments/webhooks/portone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("webhook-id", "msg_1")
                        .header("webhook-timestamp", "1786410000")
                        .header("webhook-signature", "v1,signature")
                        .content(RAW_BODY))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("서명 실패는 PAYMENT_006 401로 반환한다")
    void rejectsInvalidSignature() throws Exception {
        given(webhookService.handle(
                RAW_BODY, "msg_1", "1786410000", "v1,bad"))
                .willThrow(new ServiceException(PaymentErrorCode.INVALID_WEBHOOK_SIGNATURE));

        mockMvc.perform(post("/api/v1/payments/webhooks/portone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("webhook-id", "msg_1")
                        .header("webhook-timestamp", "1786410000")
                        .header("webhook-signature", "v1,bad")
                        .content(RAW_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PAYMENT_006"));
    }
}
