package com.miriyum.domain.payment.config;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    RestClient.Builder portOneRestClientBuilder() {
        return RestClient.builder();
    }

    public static class PortOne {
        private String apiSecret;
        private String webhookSecret;
        private String storeId;
        private String baseUrl = "https://api.portone.io";

        public String requireApiSecret() {
            return requireConfigured(apiSecret, "PortOne API secret");
        }

        public String requireWebhookSecret() {
            return requireConfigured(webhookSecret, "PortOne webhook secret");
        }

        public String requireStoreId() {
            return requireConfigured(storeId, "PortOne store ID");
        }

        public String getApiSecret() { return apiSecret; }
        public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
        public String getStoreId() { return storeId; }
        public void setStoreId(String storeId) { this.storeId = storeId; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        private static String requireConfigured(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            return value;
        }
    }
}
