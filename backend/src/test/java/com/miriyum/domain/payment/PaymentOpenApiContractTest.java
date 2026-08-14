package com.miriyum.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PaymentOpenApiContractTest {

    private static final Path SPECS = Path.of("..", "docs", "specs");
    private static final Set<String> CONSUMER_PATHS = Set.of(
            "/api/v1/consumers/me/payments",
            "/api/v1/consumers/me/payments/{paymentId}",
            "/api/v1/consumers/me/payments/{paymentId}/confirmations"
    );
    private static final String WEBHOOK_PATH = "/api/v1/payments/webhooks/portone";

    @Test
    void featureContractIsExposedByItsAudienceButRemainsOutsideMvp1Aggregate() throws Exception {
        Map<String, Object> payment = paths("payment/openapi.yaml");
        Map<String, Object> consumer = paths("consumer-openapi.yaml");
        Map<String, Object> publicApi = paths("public-openapi.yaml");
        Map<String, Object> aggregate = paths("mvp1-openapi.yaml");

        assertThat(payment.keySet()).containsExactlyInAnyOrderElementsOf(
                Set.of(
                        "/api/v1/consumers/me/payments",
                        "/api/v1/consumers/me/payments/{paymentId}",
                        "/api/v1/consumers/me/payments/{paymentId}/confirmations",
                        WEBHOOK_PATH
                )
        );
        assertThat(consumer.keySet()).containsAll(CONSUMER_PATHS);
        assertThat(CONSUMER_PATHS).noneMatch(publicApi::containsKey);
        assertThat(CONSUMER_PATHS).noneMatch(aggregate::containsKey);
        assertThat(consumer).doesNotContainKey(WEBHOOK_PATH);
        assertThat(publicApi).containsKey(WEBHOOK_PATH);
        assertThat(aggregate).doesNotContainKey(WEBHOOK_PATH);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths(String file) throws Exception {
        try (InputStream input = Files.newInputStream(SPECS.resolve(file))) {
            return (Map<String, Object>) ((Map<String, Object>) new Yaml().load(input)).get("paths");
        }
    }
}
