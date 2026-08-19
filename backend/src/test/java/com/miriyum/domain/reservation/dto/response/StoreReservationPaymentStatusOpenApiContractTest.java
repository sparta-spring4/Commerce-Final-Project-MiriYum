package com.miriyum.domain.reservation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class StoreReservationPaymentStatusOpenApiContractTest {

    private static final String PATH = "/api/v1/store-operators/stores/{storeId}"
            + "/reservations/{reservationId}/payment-status";

    @Test
    void storeOperatorAudiencePublishesStrictReservationPaymentStatusContract()
            throws IOException {
        Map<String, Object> audience = load(
                Path.of("..", "docs", "specs", "store-operator-openapi.yaml"));
        assertThat(map(map(audience.get("paths")).get(PATH)))
                .containsEntry("$ref", "./store-payment-status/openapi.yaml#/paths/"
                        + "~1api~1v1~1store-operators~1stores~1{storeId}~1reservations"
                        + "~1{reservationId}~1payment-status");

        Map<String, Object> contract = load(
                Path.of("..", "docs", "specs", "store-payment-status", "openapi.yaml"));
        Map<String, Object> operation = map(map(map(contract.get("paths")).get(PATH)).get("get"));

        assertThat(operation).containsEntry("operationId", "getStoreReservationPaymentStatus");
        assertThat(list(operation.get("security"))).anySatisfy(requirement ->
                assertThat(map(requirement)).containsKey("bearerAuth"));
        assertThat(list(operation.get("parameters")))
                .allSatisfy(parameter -> assertThat(map(parameter))
                        .containsEntry("in", "path"))
                .extracting(parameter -> String.valueOf(map(parameter).get("name")))
                .containsExactly("storeId", "reservationId");
        Map<String, Object> responses = map(operation.get("responses"));
        assertThat(responses.keySet())
                .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "503");
        assertThat(map(responses.get("401"))).containsEntry(
                "$ref", "../mvp1-common/openapi.yaml#/components/responses/Unauthorized");
        assertThat(map(responses.get("403"))).containsEntry(
                "$ref", "../mvp1-common/openapi.yaml#/components/responses/Forbidden");

        Map<String, Object> schemas = map(map(contract.get("components")).get("schemas"));
        assertStrictSchema(schemas, "StoreReservationPaymentStatus",
                Set.of("reservationId", "result", "reconciliationRequired", "observedAt", "payment"));
        assertStrictSchema(schemas, "StoreReservationPayment",
                Set.of("paymentId", "amountMinor", "refundedAmountMinor",
                        "refundableAmountMinor", "currency", "status", "lastAttemptStatus",
                        "createdAt", "paidAt", "updatedAt", "refunds"));
        assertStrictSchema(schemas, "StoreReservationRefund",
                Set.of("refundId", "amountMinor", "status", "requestedAt", "completedAt"));
        assertThat(list(map(schemas.get("StorePaymentResult")).get("enum")))
                .containsExactly("NOT_APPLICABLE", "AWAITING_PAYMENT", "PROCESSING",
                        "FAILED", "COMPLETED", "UNKNOWN");

        Set<String> responseCodes = map(map(contract.get("components")).get("responses"))
                .keySet().stream().map(String::valueOf).collect(Collectors.toSet());
        assertThat(responseCodes).contains("StoreOrReservationNotFound", "PaymentSourceUnavailable");
    }

    private static void assertStrictSchema(
            Map<String, Object> schemas,
            String name,
            Set<String> required
    ) {
        Map<String, Object> schema = map(schemas.get(name));
        assertThat(schema).containsEntry("type", "object")
                .containsEntry("additionalProperties", false);
        assertThat(list(schema.get("required")).stream().map(String::valueOf).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(required);
    }

    private static Map<String, Object> load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
