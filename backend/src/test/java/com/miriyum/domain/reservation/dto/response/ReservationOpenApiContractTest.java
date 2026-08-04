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
        Map<String, Object> document = load(contract);

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

    @Test
    void operatorTimePolicyLifecycleUsesCanonicalVersionedContracts() throws IOException {
        Map<String, Object> document = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );
        Map<String, Object> paths = map(document.get("paths"));

        String draftsPath =
                "/api/v1/store-operator/stores/{storeId}/reservation-time-policies";
        String publicationPath = draftsPath + "/{version}/publication";
        String cancellationPath = draftsPath + "/{version}/publication-cancellation";

        assertThat(paths).containsKeys(draftsPath, publicationPath, cancellationPath);
        assertThat(paths).doesNotContainKeys(
                "/store-operator/stores/{storeId}/reservation-time-policies",
                "/store-operator/stores/{storeId}/reservation-time-policies/{version}/publication",
                "/store-operator/stores/{storeId}/reservation-time-policies/{version}"
                        + "/publication-cancellation"
        );

        assertPolicyCommand(
                map(map(paths.get(draftsPath)).get("put")),
                "#/components/schemas/ReservationTimePolicyDraftRequest"
        );
        assertPolicyCommand(
                map(map(paths.get(publicationPath)).get("post")),
                "#/components/schemas/ReservationTimePolicyPublicationRequest"
        );
        assertPolicyCommand(
                map(map(paths.get(cancellationPath)).get("post")),
                "#/components/schemas/ReservationTimePolicyPublicationCancellationRequest"
        );

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        assertThat(schemas).containsKeys(
                "ReservationTimePolicyDraftRequest",
                "ReservationTimePolicyPublicationRequest",
                "ReservationTimePolicyPublicationCancellationRequest",
                "ReservationTimePolicyResponse",
                "ReservationTimePolicyStatus"
        );
        assertThat(list(map(schemas.get("ReservationTimePolicyStatus")).get("enum")))
                .containsExactly(
                        "DRAFT",
                        "SCHEDULED",
                        "ACTIVE",
                        "RETIRED",
                        "ACTIVATION_FAILED"
                );

        Map<String, Object> responses = map(map(document.get("components")).get("responses"));
        assertThat(responses).containsKey("ReservationTimePolicyConflict");
        assertThat(responses.get("ReservationTimePolicyConflict").toString())
                .contains("RESERVATION_010");

        Map<String, Object> aggregate = load(
                Path.of("..", "docs", "specs", "mvp1-openapi.yaml")
        );
        Map<String, Object> aggregatePaths = map(aggregate.get("paths"));
        assertThat(map(aggregatePaths.get(draftsPath)))
                .containsEntry(
                        "$ref",
                        "./reservation/openapi.yaml#/paths/"
                                + "~1api~1v1~1store-operator~1stores~1{storeId}"
                                + "~1reservation-time-policies"
                );
        assertThat(map(aggregatePaths.get(publicationPath)))
                .containsEntry(
                        "$ref",
                        "./reservation/openapi.yaml#/paths/"
                                + "~1api~1v1~1store-operator~1stores~1{storeId}"
                                + "~1reservation-time-policies~1{version}~1publication"
                );
        assertThat(map(aggregatePaths.get(cancellationPath)))
                .containsEntry(
                        "$ref",
                        "./reservation/openapi.yaml#/paths/"
                                + "~1api~1v1~1store-operator~1stores~1{storeId}"
                                + "~1reservation-time-policies~1{version}"
                                + "~1publication-cancellation"
                );
    }

    @Test
    void capacityRequestRequiresPositivePeopleAndAllowsZeroTeams() throws IOException {
        Map<String, Object> document = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> properties = map(
                map(schemas.get("CapacityBucketRequest")).get("properties")
        );

        assertThat(map(properties.get("maxPeople")))
                .containsEntry("minimum", 1);
        assertThat(map(properties.get("maxTeams")))
                .containsEntry("minimum", 0);
    }

    private static void assertPolicyCommand(
            Map<String, Object> operation,
            String requestSchemaRef
    ) {
        assertThat(list(operation.get("security"))).anySatisfy(requirement ->
                assertThat(map(requirement)).containsKey("bearerAuth"));
        assertThat(list(operation.get("parameters"))).anySatisfy(parameter ->
                assertThat(map(parameter)).containsEntry(
                        "$ref",
                        "../mvp1-common/openapi.yaml#/components/parameters/IdempotencyKey"
                ));

        Map<String, Object> requestBody = map(operation.get("requestBody"));
        Map<String, Object> content = map(requestBody.get("content"));
        Map<String, Object> json = map(content.get("application/json"));
        assertThat(map(json.get("schema"))).containsEntry("$ref", requestSchemaRef);

        assertThat(map(operation.get("responses")))
                .containsKeys("200", "400", "401", "403", "404", "409");
    }

    private static Map<String, Object> load(Path contract) throws IOException {
        try (InputStream input = Files.newInputStream(contract)) {
            return new Yaml().load(input);
        }
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
