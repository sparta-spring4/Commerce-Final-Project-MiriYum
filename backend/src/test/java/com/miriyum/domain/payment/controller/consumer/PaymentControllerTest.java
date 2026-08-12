package com.miriyum.domain.payment.controller.consumer;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.payment.config.PaymentSecurityConfig;
import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistoryQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistorySlice;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentAttemptStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = PaymentController.class,
        properties = "miriyum.payment.enabled=true"
)
@Import({PaymentSecurityConfig.class, GlobalExceptionHandler.class})
class PaymentControllerTest {

    private static final String PAYMENT_ID = "900000000000000001";
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("소비자 JWT와 Idempotency-Key로 서버 검증 확정만 요청한다")
    void confirmsOwnedPayment() throws Exception {
        authenticateConsumer();
        PaymentResult result = result(PaymentStatus.PAID, PaymentAttemptStatus.PAID);
        given(paymentService.confirmPayment(new ConfirmPaymentCommand(
                PAYMENT_ID,
                11L,
                "payment-reservation-900000000000000001",
                IDEMPOTENCY_KEY
        ))).willReturn(result);

        mockMvc.perform(post("/api/v1/consumers/payments/{paymentId}/confirmations", PAYMENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"portOnePaymentId":"payment-reservation-900000000000000001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.paymentId").value(PAYMENT_ID))
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    @Test
    @DisplayName("불명확한 PortOne 결과는 202와 RECONCILIATION_REQUIRED로 반환한다")
    void returnsAcceptedForReconciliation() throws Exception {
        authenticateConsumer();
        given(paymentService.confirmPayment(new ConfirmPaymentCommand(
                PAYMENT_ID,
                11L,
                "payment-reservation-900000000000000001",
                IDEMPOTENCY_KEY
        ))).willReturn(result(
                PaymentStatus.RECONCILIATION_REQUIRED,
                PaymentAttemptStatus.UNKNOWN
        ));

        mockMvc.perform(post("/api/v1/consumers/payments/{paymentId}/confirmations", PAYMENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"portOnePaymentId":"payment-reservation-900000000000000001"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("RECONCILIATION_REQUIRED"));
    }

    @Test
    @DisplayName("PortOne PAY_PENDING은 완료로 표시하지 않고 202 대사 상태로 반환한다")
    void returnsAcceptedWhileProviderPaymentIsPending() throws Exception {
        authenticateConsumer();
        given(paymentService.confirmPayment(new ConfirmPaymentCommand(
                PAYMENT_ID,
                11L,
                "payment-reservation-900000000000000001",
                IDEMPOTENCY_KEY
        ))).willReturn(result(
                PaymentStatus.RECONCILIATION_REQUIRED,
                PaymentAttemptStatus.UNKNOWN));

        mockMvc.perform(post("/api/v1/consumers/payments/{paymentId}/confirmations", PAYMENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"portOnePaymentId":"payment-reservation-900000000000000001"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("RECONCILIATION_REQUIRED"));
    }

    @Test
    @DisplayName("본인 결제 이력은 기본 size 20과 상태 filter로 조회한다")
    void getsOwnedPaymentHistory() throws Exception {
        authenticateConsumer();
        PaymentHistoryQuery query = new PaymentHistoryQuery(11L, PaymentStatus.PAID, 20, null);
        given(paymentService.getConsumerPaymentHistory(query)).willReturn(
                new PaymentHistorySlice(List.of(result(
                        PaymentStatus.PAID, PaymentAttemptStatus.PAID)), null, false));

        mockMvc.perform(get("/api/v1/consumers/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .queryParam("status", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].paymentId").value(PAYMENT_ID))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        then(paymentService).should().getConsumerPaymentHistory(query);
    }

    @Test
    @DisplayName("소비자 JWT가 없으면 Payment Service에 도달하지 않는다")
    void rejectsMissingConsumerToken() throws Exception {
        mockMvc.perform(get("/api/v1/consumers/payments/{paymentId}", PAYMENT_ID))
                .andExpect(status().isUnauthorized());

        then(paymentService).shouldHaveNoInteractions();
    }

    private void authenticateConsumer() {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 11L));
    }

    private static PaymentResult result(
            PaymentStatus status,
            PaymentAttemptStatus attemptStatus
    ) {
        Instant now = Instant.parse("2026-08-11T01:00:00Z");
        return new PaymentResult(
                PAYMENT_ID,
                "123",
                30_000L,
                0L,
                30_000L,
                "KRW",
                status,
                attemptStatus,
                now.minusSeconds(60),
                status == PaymentStatus.PAID ? now : null,
                now,
                List.of()
        );
    }
}
