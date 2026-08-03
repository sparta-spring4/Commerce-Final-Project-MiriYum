package com.miriyum.domain.reservation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ReservationOpenApiContractTest {

    @Test
    void perStoreTimeAndLegacyCustomerResponseRemainExplicit() throws IOException {
        Path contract = Path.of("..", "docs", "specs", "reservation", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        assertThat(map(schemas.get("StoreLocalDate")).get("description").toString())
                .contains("IANA")
                .doesNotContain("Asia/Seoul");
        assertThat(map(schemas.get("StoreLocalTime")).get("description").toString())
                .contains("IANA")
                .doesNotContain("Asia/Seoul");

        Map<String, Object> createProperties = map(
                map(schemas.get("ReservationCreateRequest")).get("properties")
        );
        assertThat(map(createProperties.get("serviceDate")))
                .containsEntry("$ref", "#/components/schemas/StoreLocalDate");
        assertThat(map(createProperties.get("startTime")))
                .containsEntry("$ref", "#/components/schemas/StoreLocalTime");
        assertThat(map(createProperties.get("startOffset")).get("pattern"))
                .isEqualTo("^[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)$");
        assertThat(createProperties)
                .doesNotContainKeys("endTime", "serviceEndAt", "occupancyEndAt");

        assertThat(list(map(schemas.get("ReservationTimeStatus")).get("enum")))
                .containsExactly("RESOLVED", "LEGACY_UNRESOLVED");
        assertCustomerTimeShape(map(schemas.get("ReservationDetail")));
        assertCustomerTimeShape(map(schemas.get("ReservationSummary")));
    }

    private static void assertCustomerTimeShape(Map<String, Object> schema) {
        assertThat(list(schema.get("required")))
                .contains("serviceDate", "timeStatus", "startAt", "serviceEndAt", "timeZoneId");
        Map<String, Object> properties = map(schema.get("properties"));
        assertThat(properties)
                .doesNotContainKeys("endTime", "occupancyEndAt");
        assertThat(map(properties.get("timeStatus")))
                .containsEntry("$ref", "#/components/schemas/ReservationTimeStatus");
        assertThat(list(map(properties.get("startAt")).get("oneOf"))).hasSize(2);
        assertThat(list(map(properties.get("serviceEndAt")).get("oneOf"))).hasSize(2);
        assertThat(list(map(properties.get("timeZoneId")).get("type")))
                .containsExactly("string", "null");
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
