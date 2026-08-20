package com.miriyum.domain.payment.adapter.portone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.miriyum.domain.payment.config.PaymentSettings;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderStatus;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class PortOnePaymentClientTest {

    private MockRestServiceServer server;
    private PortOnePaymentClient client;

    @BeforeEach
    void setUp() {
        PaymentSettings settings = new PaymentSettings();
        settings.getPortone().setApiSecret("test-api-secret");
        settings.getPortone().setStoreId("store-1");
        settings.getPortone().setBaseUrl("https://api.portone.test");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PortOnePaymentClient(builder, settings, new ObjectMapper());
    }

    @Test
    @DisplayName("PortOne V2 결제 단건 조회는 configured 상점에서 core snapshot을 반환한다")
    void getsVerifiedPaymentSnapshot() {
        server.expect(requestTo(
                        "https://api.portone.test/payments/payment-reservation-900000000000000001"
                                + "?storeId=store-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "PortOne test-api-secret"))
                .andRespond(withSuccess("""
                        {
                          "status": "PAID",
                          "id": "payment-reservation-900000000000000001",
                          "transactionId": "transaction-1",
                          "amount": {"total": 30000},
                          "currency": "KRW",
                          "cancellations": [
                            {
                              "status": "SUCCEEDED",
                              "id": "cancellation-1",
                              "totalAmount": 10000,
                              "reason": "예약 취소 [MIRIYUM_REFUND_ID=910000000000000001]",
                              "requestedAt": "2026-08-18T00:00:00Z"
                            }
                          ],
                          "method": {"type": "CARD", "card": {"number": "1234********5678"}}
                        }
                        """, MediaType.APPLICATION_JSON));

        ProviderPayment payment = client.getPayment(
                "payment-reservation-900000000000000001");

        assertThat(payment).isEqualTo(new ProviderPayment(
                "payment-reservation-900000000000000001",
                "transaction-1",
                ProviderStatus.PAID,
                30_000L,
                "KRW",
                List.of(new ProviderCancellation(
                        "cancellation-1",
                        ProviderStatus.PARTIALLY_CANCELLED,
                        10_000L,
                        "KRW",
                        "예약 취소 [MIRIYUM_REFUND_ID=910000000000000001]"))
        ));
        server.verify();
    }

    @Test
    @DisplayName("환불은 내부 refundId와 금액을 PortOne V2 취소 요청에 고정한다")
    void cancelsPaymentWithInternalRefundReference() {
        server.expect(requestTo(
                        "https://api.portone.test/payments/payment-reservation-900000000000000001/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "PortOne test-api-secret"))
                .andExpect(header("Idempotency-Key", "\"910000000000000001\""))
                .andExpect(content().json("""
                        {
                          "storeId": "store-1",
                          "amount": 10000,
                          "reason": "예약 취소 [MIRIYUM_REFUND_ID=910000000000000001]",
                          "requester": "CUSTOMER"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "cancellation": {
                            "status": "SUCCEEDED",
                            "id": "cancellation-1",
                            "totalAmount": 10000
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ProviderCancellation cancellation = client.cancelPayment(
                "payment-reservation-900000000000000001",
                "910000000000000001",
                10_000L,
                "KRW",
                "예약 취소"
        );

        assertThat(cancellation).isEqualTo(new ProviderCancellation(
                "cancellation-1", ProviderStatus.PARTIALLY_CANCELLED, 10_000L, "KRW"));
        server.verify();
    }

    @Test
    @DisplayName("PortOne 5xx는 성공이나 실패로 단정하지 않고 불명확 결과로 전달한다")
    void mapsProvider5xxToUnavailableResult() {
        server.expect(requestTo(
                        "https://api.portone.test/payments/payment-reservation-900000000000000001"
                                + "?storeId=store-1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getPayment(
                "payment-reservation-900000000000000001"))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @ParameterizedTest(name = "lookup body={0}")
    @ValueSource(strings = {"{", "", "   \r\n"})
    @DisplayName("PortOne 결제 조회의 malformed 또는 빈 2xx 응답은 결과 불명으로 변환한다")
    void mapsInvalidPaymentResponseToUnavailableResult(String responseBody) {
        server.expect(requestTo(
                        "https://api.portone.test/payments/payment-reservation-900000000000000001"
                                + "?storeId=store-1"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getPayment(
                "payment-reservation-900000000000000001"))
                .isInstanceOf(ProviderUnavailableException.class);
        server.verify();
    }

    @ParameterizedTest(name = "cancel body={0}")
    @ValueSource(strings = {"{", "", "   \r\n"})
    @DisplayName("PortOne 취소의 malformed 또는 빈 2xx 응답은 결과 불명으로 변환한다")
    void mapsInvalidCancellationResponseToUnavailableResult(String responseBody) {
        server.expect(requestTo(
                        "https://api.portone.test/payments/payment-reservation-900000000000000001/cancel"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.cancelPayment(
                "payment-reservation-900000000000000001",
                "910000000000000001",
                10_000L,
                "KRW",
                "RESERVATION_CANCELLED"))
                .isInstanceOf(ProviderUnavailableException.class);
        server.verify();
    }

    @ParameterizedTest(name = "transaction field={0}")
    @ValueSource(strings = {"", "\"transactionId\": \"   \","})
    @DisplayName("PAID 응답의 transactionId 누락 또는 공백은 결과 불명으로 변환한다")
    void requiresTransactionIdForPaidPayment(String transactionField) {
        server.expect(requestTo(
                        "https://api.portone.test/payments/payment-reservation-900000000000000001"
                                + "?storeId=store-1"))
                .andRespond(withSuccess("""
                        {
                          "status": "PAID",
                          "id": "payment-reservation-900000000000000001",
                          %s
                          "amount": {"total": 30000},
                          "currency": "KRW"
                        }
                        """.formatted(transactionField), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getPayment(
                "payment-reservation-900000000000000001"))
                .isInstanceOf(ProviderUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("PortOne 취소 이력의 marker reason 누락은 추정하지 않고 결과 불명으로 변환한다")
    void rejectsCancellationSnapshotWithoutReasonMarker() {
        server.expect(requestTo(
                        "https://api.portone.test/payments/payment-reservation-900000000000000001"
                                + "?storeId=store-1"))
                .andRespond(withSuccess("""
                        {
                          "status": "PARTIAL_CANCELLED",
                          "id": "payment-reservation-900000000000000001",
                          "transactionId": "transaction-1",
                          "amount": {"total": 30000},
                          "currency": "KRW",
                          "cancellations": [
                            {
                              "status": "SUCCEEDED",
                              "id": "cancellation-1",
                              "totalAmount": 10000,
                              "requestedAt": "2026-08-18T00:00:00Z"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getPayment(
                "payment-reservation-900000000000000001"))
                .isInstanceOf(ProviderUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("응답을 끝내지 않는 PortOne 서버도 버전된 제한 시간 안에 불명확 결과로 반환한다")
    void timesOutHangingProviderResponse() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer hangingServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        hangingServer.setExecutor(serverExecutor);
        hangingServer.createContext("/", exchange -> {
            requestReceived.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        hangingServer.start();

        try {
            String baseUrl = "http://127.0.0.1:" + hangingServer.getAddress().getPort();
            PaymentSettings timeoutSettings = new PaymentSettings();
            timeoutSettings.getPortone().setApiSecret("test-api-secret");
            timeoutSettings.getPortone().setStoreId("store-1");
            timeoutSettings.getPortone().setBaseUrl(baseUrl);
            timeoutSettings.getPortone().setTimeoutPolicyVersion("portone-v2-v1");
            timeoutSettings.getPortone().setConnectTimeout(Duration.ofMillis(100));
            timeoutSettings.getPortone().setReadTimeout(Duration.ofMillis(100));
            PortOnePaymentClient timeoutClient = new PortOnePaymentClient(
                    timeoutSettings.portOneRestClientBuilder(),
                    timeoutSettings,
                    new ObjectMapper()
            );

            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThatThrownBy(() -> timeoutClient.getPayment(
                            "payment-reservation-900000000000000001"))
                            .isInstanceOf(ProviderUnavailableException.class));
            assertThat(requestReceived.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseResponse.countDown();
            hangingServer.stop(0);
            serverExecutor.close();
        }
    }
}
