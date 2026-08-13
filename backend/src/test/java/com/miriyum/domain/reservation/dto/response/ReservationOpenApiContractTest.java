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

class ReservationOpenApiContractTest {

    @Test
    void publicReservationApiMatrixKeepsAllTwelveOperationsExplicit() throws IOException {
        Map<String, Object> document = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );
        Map<String, Object> paths = map(document.get("paths"));
        List<OperationContract> contracts = List.of(
                new OperationContract(
                        "/api/v1/consumers/me/reservations", "post", "createReservation",
                        "#/components/schemas/ReservationCreateRequest",
                        Set.of("201", "400", "401", "403", "404", "409", "503")),
                new OperationContract(
                        "/api/v1/consumers/me/reservations", "get",
                        "getCurrentConsumerReservations", null,
                        Set.of("200", "400", "401", "403")),
                new OperationContract(
                        "/api/v1/consumers/me/reservations/{reservationId}", "get", "getReservation",
                        null, Set.of("200", "401", "403", "404")),
                new OperationContract(
                        "/api/v1/consumers/me/reservations/{reservationId}/cancellations", "post",
                        "cancelReservationByConsumer",
                        "#/components/schemas/ConsumerCancellationRequest",
                        Set.of("200", "400", "401", "403", "404", "409")),
                new OperationContract(
                        "/api/v1/store-operators/stores/{storeId}/reservations", "get",
                        "getStoreReservations", null,
                        Set.of("200", "400", "401", "403", "404")),
                new OperationContract(
                        "/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}",
                        "get", "getStoreReservation", null,
                        Set.of("200", "400", "401", "403", "404")),
                new OperationContract(
                        "/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}"
                                + "/cancellations",
                        "post", "cancelReservationByStoreOperator",
                        "#/components/schemas/StoreCancellationRequest",
                        Set.of("200", "400", "401", "403", "404", "409")),
                new OperationContract(
                        "/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}"
                                + "/fulfillments",
                        "post", "fulfillReservation",
                        "#/components/schemas/EmptyCommandRequest",
                        Set.of("200", "400", "401", "403", "404", "409")),
                new OperationContract(
                        "/api/v1/store-operators/stores/{storeId}"
                                + "/reservation-capacities/{serviceDate}",
                        "put", "replaceReservationCapacities",
                        "#/components/schemas/ReservationCapacitiesRequest",
                        Set.of("200", "400", "401", "403", "404", "409")),
                new OperationContract(
                        "/api/v1/store-operators/stores/{storeId}/reservation-time-policies",
                        "put", "createReservationTimePolicyDraft",
                        "#/components/schemas/ReservationTimePolicyDraftRequest",
                        Set.of("200", "400", "401", "403", "404", "409")),
                new OperationContract(
                        "/api/v1/store-operators/stores/{storeId}/reservation-time-policies"
                                + "/{version}/publications",
                        "post", "publishReservationTimePolicyDraft",
                        "#/components/schemas/ReservationTimePolicyPublicationRequest",
                        Set.of("200", "400", "401", "403", "404", "409")),
                new OperationContract(
                        "/api/v1/store-operators/stores/{storeId}/reservation-time-policies"
                                + "/{version}/publication-cancellations",
                        "post", "cancelReservationTimePolicyPublication",
                        "#/components/schemas/ReservationTimePolicyPublicationCancellationRequest",
                        Set.of("200", "400", "401", "403", "404", "409"))
        );

        assertThat(paths.keySet())
                .hasSize(11)
                .contains("/api/v1/consumers/me/reservations")
                .containsAll(contracts.stream().map(OperationContract::path).toList());
        Map<String, Object> aggregate = load(
                Path.of("..", "docs", "specs", "mvp1-openapi.yaml")
        );
        Map<String, Object> aggregatePaths = map(aggregate.get("paths"));
        assertThat(contracts).hasSize(12).allSatisfy(contract -> {
            Map<String, Object> pathItem = map(paths.get(contract.path()));
            assertThat(pathItem).containsKey(contract.method());
            Map<String, Object> operation = map(pathItem.get(contract.method()));

            assertThat(operation).containsEntry("operationId", contract.operationId());
            assertThat(list(operation.get("security"))).anySatisfy(requirement ->
                    assertThat(map(requirement)).containsKey("bearerAuth"));
            assertThat(map(operation.get("responses")).keySet())
                    .containsExactlyInAnyOrderElementsOf(contract.responseStatuses());

            if (contract.requestSchemaRef() == null) {
                assertThat(operation).doesNotContainKey("requestBody");
                assertThat(list(operation.get("parameters"))).allSatisfy(parameter ->
                        assertThat(map(parameter)).doesNotContainEntry(
                                "$ref",
                                "../mvp1-common/openapi.yaml#/components/parameters/IdempotencyKey"
                        ));
            } else {
                Map<String, Object> requestBody = map(operation.get("requestBody"));
                assertThat(requestBody).containsEntry("required", true);
                Map<String, Object> json = map(
                        map(requestBody.get("content")).get("application/json")
                );
                assertThat(map(json.get("schema")))
                        .containsEntry("$ref", contract.requestSchemaRef());
                assertThat(list(operation.get("parameters"))).anySatisfy(parameter ->
                        assertThat(map(parameter)).containsEntry(
                                "$ref",
                                "../mvp1-common/openapi.yaml#/components/parameters/IdempotencyKey"
                        ));
            }

            assertThat(map(aggregatePaths.get(contract.path())))
                    .containsOnlyKeys("$ref")
                    .containsEntry(
                            "$ref",
                            (contract.path().startsWith("/api/v1/consumers/")
                                    ? "./consumer-openapi.yaml#/paths/"
                                    : "./store-operator-openapi.yaml#/paths/")
                                    + escapeJsonPointer(contract.path())
                    );
        });
    }

    @Test
    void storeReservationDetailDocumentsPositivePathIdsAndBadRequest() throws IOException {
        Map<String, Object> document = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );
        Map<String, Object> operation = map(map(map(document.get("paths")).get(
                "/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}"
        )).get("get"));

        assertThat(list(operation.get("parameters")).stream()
                .map(ReservationOpenApiContractTest::map)
                .map(parameter -> parameter.get("$ref")))
                .containsExactlyInAnyOrder(
                        "#/components/parameters/StoreId",
                        "#/components/parameters/ReservationId"
                );
        assertThat(map(map(operation.get("responses")).get("400")))
                .containsEntry(
                        "$ref",
                        "../mvp1-common/openapi.yaml#/components/responses/BadRequest"
                );

        Map<String, Object> common = load(
                Path.of("..", "docs", "specs", "mvp1-common", "openapi.yaml")
        );
        assertThat(map(map(map(common.get("components")).get("schemas")).get("PublicId")))
                .containsEntry("type", "string")
                .containsEntry("pattern", "^[1-9][0-9]*$");
    }

    @Test
    void missingStoreResponsesMatchRuntimeErrors() throws IOException {
        Map<String, Object> document = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );
        Map<String, Object> paths = map(document.get("paths"));

        assertNotFoundResponse(
                document,
                map(map(paths.get("/api/v1/consumers/me/reservations")).get("post")),
                "#/components/responses/ReservationCreationNotFound",
                Set.of("STORE_001", "STORE_009", "MENU_HOLD_003")
        );
        assertNotFoundResponse(
                document,
                map(map(paths.get(
                        "/api/v1/store-operators/stores/{storeId}/reservations"
                )).get("get")),
                "#/components/responses/StoreNotFound",
                Set.of("STORE_001")
        );
        assertNotFoundResponse(
                document,
                map(map(paths.get(
                        "/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}"
                )).get("get")),
                "#/components/responses/StoreReservationNotFound",
                Set.of("STORE_001", "RESERVATION_001")
        );
        assertNotFoundResponse(
                document,
                map(map(paths.get(
                        "/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}"
                                + "/cancellations"
                )).get("post")),
                "#/components/responses/StoreReservationNotFound",
                Set.of("STORE_001", "RESERVATION_001")
        );
        assertNotFoundResponse(
                document,
                map(map(paths.get(
                        "/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}"
                                + "/fulfillments"
                )).get("post")),
                "#/components/responses/StoreReservationNotFound",
                Set.of("STORE_001", "RESERVATION_001")
        );

        Map<String, Object> responses = map(map(document.get("components")).get("responses"));
        assertThat(responses)
                .containsKey("StoreReservationNotFound")
                .doesNotContainKey("ReservationFulfillmentNotFound");
    }

    @Test
    void reservationCreationConflictDocumentsMenuHoldRuntimeErrors() throws IOException {
        Map<String, Object> document = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );
        Map<String, Object> operation = map(map(map(document.get("paths")).get(
                "/api/v1/consumers/me/reservations"
        )).get("post"));
        Map<String, Object> conflict = resolveLocalResponse(document, operation, "409");
        Map<String, Object> examples = map(
                map(map(conflict.get("content")).get("application/json")).get("examples")
        );

        assertThat(examples.values().stream()
                .map(ReservationOpenApiContractTest::map)
                .map(example -> map(example.get("value")).get("code")))
                .containsExactlyInAnyOrder(
                        "RESERVATION_003",
                        "ACCOUNT_006",
                        "MENU_HOLD_001",
                        "MENU_HOLD_002"
                );
    }

    @Test
    void consumerHistoryIsReservationOwnedWithCanonicalTimeShape() throws IOException {
        Map<String, Object> reservation = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );

        Map<String, Object> reservationSchemas =
                map(map(reservation.get("components")).get("schemas"));
        assertThat(reservationSchemas).containsKeys(
                "ReservationHistoryStatus",
                "ReservationHistoryItem",
                "ReservationHistoryPageData"
        );
        assertThat(list(map(reservationSchemas.get("ReservationHistoryStatus")).get("enum")))
                .containsExactly("CONFIRMED", "CANCELLED", "FULFILLED");

        Map<String, Object> item = map(reservationSchemas.get("ReservationHistoryItem"));
        assertCustomerTimeShape(
                item,
                "#/components/schemas/ReservationTimeStatus"
        );
        assertThat(map(item.get("properties")))
                .containsKeys(
                        "reservationId",
                        "storeId",
                        "storeName",
                        "partySize",
                        "status",
                        "createdAt"
                )
                .doesNotContainKeys("startTime", "endTime", "cancelledBy");

        Map<String, Object> pageProperties = map(
                map(reservationSchemas.get("ReservationHistoryPageData")).get("properties")
        );
        assertThat(map(map(pageProperties.get("items")).get("items"))).containsEntry(
                "$ref",
                "#/components/schemas/ReservationHistoryItem"
        );
        Map<String, Object> successProperties = map(
                map(reservationSchemas.get("ReservationHistoryPageSuccessResponse")).get("properties")
        );
        assertThat(map(successProperties.get("data"))).containsEntry(
                "$ref",
                "#/components/schemas/ReservationHistoryPageData"
        );

        Map<String, Object> reservationPaths = map(reservation.get("paths"));
        Map<String, Object> operation = map(map(reservationPaths.get(
                "/api/v1/consumers/me/reservations"
        )).get("get"));
        Map<String, Object> statusParameter = list(operation.get("parameters")).stream()
                .map(ReservationOpenApiContractTest::map)
                .filter(parameter -> "status".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(map(statusParameter.get("schema"))).containsEntry(
                "$ref",
                "#/components/schemas/ReservationHistoryStatus"
        );
        Map<String, Object> sortParameter = list(operation.get("parameters")).stream()
                .map(ReservationOpenApiContractTest::map)
                .filter(parameter -> "sort".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(list(map(sortParameter.get("schema")).get("enum")))
                .containsExactly(
                        "createdAt,desc",
                        "createdAt,asc",
                        "serviceDate,desc",
                        "serviceDate,asc",
                        "startAt,desc",
                        "startAt,asc"
                );
    }

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
        assertCustomerTimeShape(
                map(schemas.get("ReservationDetail")),
                "#/components/schemas/ReservationTimeStatus"
        );
        assertCustomerTimeShape(
                map(schemas.get("ReservationSummary")),
                "#/components/schemas/ReservationTimeStatus"
        );
    }

    @Test
    void operatorTimePolicyLifecycleUsesCanonicalVersionedContracts() throws IOException {
        Map<String, Object> document = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );
        Map<String, Object> paths = map(document.get("paths"));

        String draftsPath =
                "/api/v1/store-operators/stores/{storeId}/reservation-time-policies";
        String publicationPath = draftsPath + "/{version}/publications";
        String cancellationPath = draftsPath + "/{version}/publication-cancellations";

        assertThat(paths).containsKeys(draftsPath, publicationPath, cancellationPath);
        assertThat(paths).doesNotContainKeys(
                "/store-operator/stores/{storeId}/reservation-time-policies",
                "/store-operator/stores/{storeId}/reservation-time-policies/{version}/publications",
                "/store-operator/stores/{storeId}/reservation-time-policies/{version}"
                        + "/publication-cancellations"
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
                        "./store-operator-openapi.yaml#/paths/"
                                + "~1api~1v1~1store-operators~1stores~1{storeId}"
                                + "~1reservation-time-policies"
                );
        assertThat(map(aggregatePaths.get(publicationPath)))
                .containsEntry(
                        "$ref",
                        "./store-operator-openapi.yaml#/paths/"
                                + "~1api~1v1~1store-operators~1stores~1{storeId}"
                                + "~1reservation-time-policies~1{version}~1publications"
                );
        assertThat(map(aggregatePaths.get(cancellationPath)))
                .containsEntry(
                        "$ref",
                        "./store-operator-openapi.yaml#/paths/"
                                + "~1api~1v1~1store-operators~1stores~1{storeId}"
                                + "~1reservation-time-policies~1{version}"
                                + "~1publication-cancellations"
                );
    }

    @Test
    void capacityRequestAndResponseRequirePositivePeopleAndAllowZeroTeams() throws IOException {
        Map<String, Object> document = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> requestProperties = map(
                map(schemas.get("CapacityBucketRequest")).get("properties")
        );

        assertThat(map(requestProperties.get("maxPeople")))
                .containsEntry("minimum", 1);
        assertThat(map(requestProperties.get("maxTeams")))
                .containsEntry("minimum", 0);

        Map<String, Object> responseProperties = map(
                map(schemas.get("CapacityBucket")).get("properties")
        );
        assertThat(map(responseProperties.get("maxPeople")))
                .containsEntry("minimum", 1);
        assertThat(map(responseProperties.get("maxTeams")))
                .containsEntry("minimum", 0);

        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> operation = map(map(paths.get(
                "/api/v1/store-operators/stores/{storeId}"
                        + "/reservation-capacities/{serviceDate}"
        )).get("put"));
        assertThat(map(map(operation.get("responses")).get("404")))
                .containsEntry("$ref", "#/components/responses/StoreNotFound");
    }

    @Test
    void cancellationOperationsKeepReasonAndDetailContractsExplicit() throws IOException {
        Map<String, Object> document = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> consumerOperation = map(map(paths.get(
                "/api/v1/consumers/me/reservations/{reservationId}/cancellations"
        )).get("post"));
        Map<String, Object> operatorOperation = map(map(paths.get(
                "/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}/cancellations"
        )).get("post"));

        assertCancellationOperation(
                consumerOperation,
                "#/components/schemas/ConsumerCancellationRequest"
        );
        assertCancellationOperation(
                operatorOperation,
                "#/components/schemas/StoreCancellationRequest"
        );

        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> responses = map(components.get("responses"));
        Map<String, Object> reservationStateConflict = map(
                responses.get("ReservationStateConflict")
        );
        assertThat(reservationStateConflict.toString())
                .contains("RESERVATION_005", "RESERVATION_006");

        Map<String, Object> detail = map(map(components.get("schemas")).get("ReservationDetail"));
        assertThat(list(detail.get("required")))
                .contains("cancelledBy", "cancellationReason");
        assertThat(map(detail.get("properties")))
                .containsKeys("cancelledBy", "cancellationReason")
                .doesNotContainKey("cancelledAt");
    }

    @Test
    void fulfillmentOperationResolvesStrictBodyAndAllConflictExamples() throws IOException {
        Map<String, Object> document = load(
                Path.of("..", "docs", "specs", "reservation", "openapi.yaml")
        );
        Map<String, Object> operation = map(map(map(document.get("paths")).get(
                "/api/v1/store-operators/stores/{storeId}"
                        + "/reservations/{reservationId}/fulfillments"
        )).get("post"));
        Map<String, Object> json = map(
                map(map(operation.get("requestBody")).get("content")).get("application/json")
        );

        assertThat(map(json.get("schema")))
                .containsEntry("$ref", "#/components/schemas/EmptyCommandRequest");
        Map<String, Object> responses = map(operation.get("responses"));
        assertThat(responses).containsKeys("200", "400", "401", "403", "404", "409");
        assertThat(map(responses.get("403")))
                .containsEntry(
                        "$ref",
                        "#/components/responses/ReservationFulfillmentForbidden"
                );
        assertThat(map(responses.get("404")))
                .containsEntry(
                        "$ref",
                        "#/components/responses/StoreReservationNotFound"
                );
        assertThat(map(responses.get("409")))
                .containsEntry("$ref", "#/components/responses/ReservationFulfillmentConflict");

        Map<String, Object> forbidden = resolveLocalResponse(document, operation, "403");
        Map<String, Object> forbiddenExamples = map(
                map(map(forbidden.get("content")).get("application/json")).get("examples")
        );
        assertThat(forbiddenExamples.values().stream()
                .map(ReservationOpenApiContractTest::map)
                .map(example -> map(example.get("value")).get("code")))
                .containsExactlyInAnyOrder("AUTH_011", "STORE_003");

        Map<String, Object> notFound = resolveLocalResponse(document, operation, "404");
        Map<String, Object> notFoundExamples = map(
                map(map(notFound.get("content")).get("application/json")).get("examples")
        );
        assertThat(notFoundExamples.values().stream()
                .map(ReservationOpenApiContractTest::map)
                .map(example -> map(example.get("value")).get("code")))
                .containsExactlyInAnyOrder("STORE_001", "RESERVATION_001");

        Map<String, Object> conflict = resolveLocalResponse(document, operation, "409");
        Map<String, Object> examples = map(
                map(map(conflict.get("content")).get("application/json")).get("examples")
        );
        assertThat(examples.values().stream()
                .map(ReservationOpenApiContractTest::map)
                .map(example -> map(example.get("value")).get("code")))
                .containsExactlyInAnyOrder(
                        "RESERVATION_005",
                        "MENU_HOLD_006",
                        "COMMON_007",
                        "COMMON_008"
                );
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

    private static void assertCancellationOperation(
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
        assertThat(requestBody).containsEntry("required", true);
        Map<String, Object> json = map(map(requestBody.get("content")).get("application/json"));
        assertThat(map(json.get("schema"))).containsEntry("$ref", requestSchemaRef);

        Map<String, Object> responses = map(operation.get("responses"));
        assertThat(responses).containsKeys("200", "400", "401", "403", "404", "409");
        Map<String, Object> success = map(responses.get("200"));
        Map<String, Object> successJson = map(map(success.get("content")).get("application/json"));
        assertThat(map(successJson.get("schema")))
                .containsEntry("$ref", "#/components/schemas/ReservationSuccessResponse");
    }

    private static void assertNotFoundResponse(
            Map<String, Object> document,
            Map<String, Object> operation,
            String expectedReference,
            Set<String> expectedCodes
    ) {
        assertThat(map(map(operation.get("responses")).get("404")))
                .containsEntry("$ref", expectedReference);

        Map<String, Object> response = resolveLocalResponse(document, operation, "404");
        Map<String, Object> json = map(map(response.get("content")).get("application/json"));
        Set<String> actualCodes = json.containsKey("examples")
                ? map(json.get("examples")).values().stream()
                        .map(ReservationOpenApiContractTest::map)
                        .map(example -> String.valueOf(map(example.get("value")).get("code")))
                        .collect(Collectors.toSet())
                : Set.of(String.valueOf(map(json.get("example")).get("code")));
        assertThat(actualCodes).containsExactlyInAnyOrderElementsOf(expectedCodes);
    }

    private static Map<String, Object> load(Path contract) throws IOException {
        try (InputStream input = Files.newInputStream(contract)) {
            return new Yaml().load(input);
        }
    }

    private static Map<String, Object> resolveLocalResponse(
            Map<String, Object> document,
            Map<String, Object> operation,
            String status
    ) {
        String reference = String.valueOf(
                map(map(operation.get("responses")).get(status)).get("$ref")
        );
        String prefix = "#/components/responses/";
        assertThat(reference).startsWith(prefix);
        String responseName = reference.substring(prefix.length());
        return map(map(map(document.get("components")).get("responses")).get(responseName));
    }

    private static void assertCustomerTimeShape(
            Map<String, Object> schema,
            String timeStatusReference
    ) {
        assertThat(list(schema.get("required")))
                .contains("serviceDate", "timeStatus", "startAt", "serviceEndAt", "timeZoneId");
        Map<String, Object> properties = map(schema.get("properties"));
        assertThat(properties)
                .doesNotContainKeys("endTime", "occupancyEndAt");
        assertThat(map(properties.get("timeStatus")))
                .containsEntry("$ref", timeStatusReference);
        assertThat(list(map(properties.get("startAt")).get("oneOf"))).hasSize(2);
        assertThat(list(map(properties.get("serviceEndAt")).get("oneOf"))).hasSize(2);
        assertThat(list(map(properties.get("timeZoneId")).get("type")))
                .containsExactly("string", "null");
    }

    private static String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private record OperationContract(
            String path,
            String method,
            String operationId,
            String requestSchemaRef,
            Set<String> responseStatuses
    ) {
    }
}
