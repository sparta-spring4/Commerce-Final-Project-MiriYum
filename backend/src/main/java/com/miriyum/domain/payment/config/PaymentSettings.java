package com.miriyum.domain.payment.config;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Payment 비밀값과 PortOne endpoint를 환경 변수에서 주입받는 설정이다. */
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "miriyum.payment")
public class PaymentSettings {

    private String cursorSecret;
    private final PortOne portone = new PortOne();

    public String requireCursorSecret() {
        if (cursorSecret == null || cursorSecret.length() < 32) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return cursorSecret;
    }

    public String getCursorSecret() {
        return cursorSecret;
    }

    public void setCursorSecret(String cursorSecret) {
        this.cursorSecret = cursorSecret;
    }

    public PortOne getPortone() {
        return portone;
    }

    @Bean("portOneRestClientBuilder")
    public RestClient.Builder portOneRestClientBuilder() {
        PortOne portOneSettings = getPortone();
        portOneSettings.requireTimeoutPolicyVersion();
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(portOneSettings.requireConnectTimeout());
        requestFactory.setReadTimeout(portOneSettings.requireReadTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }

    public static class PortOne {
        private static final String TIMEOUT_POLICY_V1 = "portone-v2-v1";

        private String apiSecret;
        private String webhookSecret;
        private String storeId;
        private String baseUrl = "https://api.portone.io";
        private String timeoutPolicyVersion = TIMEOUT_POLICY_V1;
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(3);

        public String requireApiSecret() {
            return requireConfigured(apiSecret, "PortOne API secret");
        }

        public String requireWebhookSecret() {
            return requireConfigured(webhookSecret, "PortOne webhook secret");
        }

        public String requireStoreId() {
            return requireConfigured(storeId, "PortOne store ID");
        }

        public String requireTimeoutPolicyVersion() {
            if (!TIMEOUT_POLICY_V1.equals(timeoutPolicyVersion)) {
                throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            return timeoutPolicyVersion;
        }

        public Duration requireConnectTimeout() {
            return requirePositiveDuration(connectTimeout);
        }

        public Duration requireReadTimeout() {
            return requirePositiveDuration(readTimeout);
        }

        public String getApiSecret() { return apiSecret; }
        public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
        public String getStoreId() { return storeId; }
        public void setStoreId(String storeId) { this.storeId = storeId; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getTimeoutPolicyVersion() { return timeoutPolicyVersion; }
        public void setTimeoutPolicyVersion(String timeoutPolicyVersion) {
            this.timeoutPolicyVersion = timeoutPolicyVersion;
        }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

        private static String requireConfigured(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            return value;
        }

        private static Duration requirePositiveDuration(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            return value;
        }
    }
}
