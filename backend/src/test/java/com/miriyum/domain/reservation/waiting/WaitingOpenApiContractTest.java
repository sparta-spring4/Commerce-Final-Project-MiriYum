package com.miriyum.domain.reservation.waiting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class WaitingOpenApiContractTest {

    private static final Path CONTRACT = Path.of(
            "..", "docs", "specs", "waiting", "openapi.yaml");
    private static final Path SPEC = Path.of(
            "..", "docs", "specs", "waiting", "spec.md");
    private static final String SETTINGS_PATH =
            "/api/v1/store-operators/stores/{storeId}/waiting-settings";
    private static final String DISABLE_IMPACT_PATH = SETTINGS_PATH + "/deactivation-impact";
    private static final String CONSUMER_AVAILABILITY_PATH =
            "/api/v1/consumers/me/stores/{storeId}/waiting-availabilities";
    private static final String CONSUMER_CREATE_PATH =
            "/api/v1/consumers/me/stores/{storeId}/waiting-teams";
    private static final String CONSUMER_CURRENT_PATH =
            "/api/v1/consumers/me/waiting-teams/current";
    private static final String CONSUMER_CANCEL_PATH =
            "/api/v1/consumers/me/waiting-teams/{waitingTeamId}/cancellations";
    private static final String CONSUMER_EVENTS_PATH =
            "/api/v1/consumers/me/waiting-events";
    private static final String OPERATOR_EVENTS_PATH =
            "/api/v1/store-operators/stores/{storeId}/waiting-events";
    private static final String IDEMPOTENCY_KEY =
            "../mvp1-common/openapi.yaml#/components/parameters/IdempotencyKey";
    private static final Map<String, Set<String>> LEDGER_OPERATIONS = Map.of(
            "/api/v1/store-operators/stores/{storeId}/waiting-teams", Set.of("get"),
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}", Set.of("get"),
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/calls", Set.of("post"),
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/arrivals", Set.of("post"),
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/check-ins", Set.of("post"),
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/cancellations", Set.of("post"),
            "/api/v1/store-operators/stores/{storeId}/waiting-closure-jobs/{jobId}", Set.of("get"));

    @Test
    void autoOpenWorkerAddsNoHttpPathOperationOrSchema() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        assertThat(paths.keySet()).allSatisfy(path ->
                assertThat(path.toLowerCase()).doesNotContain("auto-open", "auto_open"));
        assertThat(schemas.keySet()).allSatisfy(schema ->
                assertThat(schema).doesNotContain(
                        "WaitingAutoOpen", "WaitingReceptionWindow"));
        Set<String> httpMethods = Set.of(
                "get", "put", "post", "delete", "options", "head", "patch", "trace");
        for (Object pathItemValue : paths.values()) {
            for (Map.Entry<String, Object> pathItem : map(pathItemValue).entrySet()) {
                if (!httpMethods.contains(pathItem.getKey())) {
                    continue;
                }
                Map<String, Object> operation = map(pathItem.getValue());
                if (operation.containsKey("operationId")) {
                    assertThat(operation.get("operationId").toString())
                            .doesNotContain("AutoOpen", "autoOpen");
                }
            }
        }
    }

    @Test
    void storeOperatorWaitingSettingsKeepTheApprovedContract() throws IOException {
        Map<String, Object> document = load(CONTRACT);

        Map<String, Object> paths = map(document.get("paths"));
        assertThat(paths)
                .containsOnlyKeys(
                        SETTINGS_PATH,
                        DISABLE_IMPACT_PATH,
                        "/api/v1/store-operators/stores/{storeId}/waiting-teams",
                        "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}",
                        "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/calls",
                        "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/arrivals",
                        "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/check-ins",
                        "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/cancellations",
                        "/api/v1/store-operators/stores/{storeId}/waiting-closure-jobs/{jobId}",
                        CONSUMER_AVAILABILITY_PATH,
                        CONSUMER_CREATE_PATH,
                        CONSUMER_CURRENT_PATH,
                        CONSUMER_CANCEL_PATH,
                        CONSUMER_EVENTS_PATH,
                        OPERATOR_EVENTS_PATH);

        Map<String, Object> settingsPath = map(paths.get(SETTINGS_PATH));
        assertThat(settingsPath).containsOnlyKeys("get", "put");

        Map<String, Object> settingsQuery = map(settingsPath.get("get"));
        assertThat(map(settingsQuery.get("responses")).keySet())
                .containsExactlyInAnyOrder(
                        "200", "400", "401", "403", "404", "409", "429");

        Map<String, Object> disableImpactPath = map(paths.get(DISABLE_IMPACT_PATH));
        assertThat(disableImpactPath).containsOnlyKeys("get");
        Map<String, Object> disableImpactQuery = map(disableImpactPath.get("get"));
        assertThat(map(disableImpactQuery.get("responses")).keySet())
                .containsExactlyInAnyOrder(
                        "200", "400", "401", "403", "404", "409", "429");

        Map<String, Object> updateOperation = map(settingsPath.get("put"));
        assertThat(list(updateOperation.get("security"))).isNotEmpty();
        assertThat(list(updateOperation.get("parameters"))).anySatisfy(parameter ->
                assertThat(map(parameter)).containsEntry("$ref", IDEMPOTENCY_KEY));
        assertThat(map(updateOperation.get("responses")).keySet())
                .containsExactlyInAnyOrder("200", "202", "400", "401", "403", "404", "409", "429");
        Map<String, Object> acceptedResponse =
                map(map(map(map(updateOperation.get("responses")).get("202")).get("content"))
                        .get("application/json"));
        assertThat(map(acceptedResponse.get("schema")))
                .containsEntry("$ref", "#/components/schemas/WaitingClosureJobSuccessResponse");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> waitingSettingProperties =
                map(map(schemas.get("WaitingSetting")).get("properties"));
        assertThat(waitingSettingProperties).containsOnlyKeys(
                "storeId", "enabled", "receptionMode", "advanceOpenMinutes", "version");
        assertThat(map(waitingSettingProperties.get("advanceOpenMinutes")))
                .containsEntry("minimum", 0)
                .containsEntry("maximum", 180);

        Map<String, Object> updateProperties =
                map(map(schemas.get("WaitingSettingUpdateRequest")).get("properties"));
        assertThat(updateProperties).containsOnlyKeys(
                "expectedVersion",
                "enabled",
                "receptionMode",
                "advanceOpenMinutes",
                "disableAction");
        assertThat(map(updateProperties.get("advanceOpenMinutes")))
                .containsEntry("minimum", 0)
                .containsEntry("maximum", 180);

        String serialized = new Yaml().dump(document);
        assertThat(serialized)
                .doesNotContain("radiusMeters", "radiusKilometers", "1000", "5000");
    }

    @Test
    void waitingChangedSseContractsAreAudienceScopedChangeSignals() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        assertWaitingEventStream(
                map(paths.get(CONSUMER_EVENTS_PATH)),
                "WaitingConsumerChangedEventStream",
                Set.of("200", "400", "401", "403", "429", "503"),
                false);
        assertWaitingEventStream(
                map(paths.get(OPERATOR_EVENTS_PATH)),
                "WaitingStoreOperatorChangedEventStream",
                Set.of("200", "400", "401", "403", "404", "429", "503"),
                true);

        Map<String, Object> invalidCursor = map(map(map(document.get("components"))
                .get("responses")).get("WaitingEventCursorBadRequest"));
        assertThat(map(invalidCursor.get("content"))).containsKey("application/json");

        assertThat(map(schemas.get("WaitingConsumerChangedEventStream"))
                .get("description").toString())
                .contains(
                        "waiting.changed",
                        "Last-Event-ID",
                        "GET /api/v1/consumers/me/waiting-teams/current",
                        "teamsAhead",
                        "keepalive");
        assertThat(map(schemas.get("WaitingStoreOperatorChangedEventStream"))
                .get("description").toString())
                .contains(
                        "waiting.changed",
                        "Last-Event-ID",
                        "해당 store",
                        "목록·상세",
                        "keepalive");
    }

    private static void assertWaitingEventStream(
            Map<String, Object> path,
            String schemaName,
            Set<String> expectedResponses,
            boolean storeScoped
    ) {
        assertThat(path)
                .containsEntry("x-miriyum-runtime-status", "contract-only")
                .containsEntry("x-miriyum-owner-issue", 250);
        Map<String, Object> operation = map(path.get("get"));
        assertThat(list(operation.get("security"))).anySatisfy(requirement ->
                assertThat(map(requirement)).containsKey("bearerAuth"));
        assertThat(list(operation.get("parameters"))).anySatisfy(parameter ->
                assertThat(map(parameter)).containsEntry(
                        "$ref", "#/components/parameters/WaitingLastEventId"));
        if (storeScoped) {
            assertThat(list(operation.get("parameters"))).anySatisfy(parameter ->
                    assertThat(map(parameter)).containsEntry(
                            "$ref", "#/components/parameters/StoreId"));
        }

        Map<String, Object> responses = map(operation.get("responses"));
        assertThat(responses.keySet()).containsExactlyInAnyOrderElementsOf(expectedResponses);
        Map<String, Object> stream = map(map(map(responses.get("200")).get("content"))
                .get("text/event-stream"));
        assertThat(map(stream.get("schema")))
                .containsEntry("$ref", "#/components/schemas/" + schemaName);
        assertThat(map(responses.get("400"))).containsEntry(
                "$ref", "#/components/responses/WaitingEventCursorBadRequest");
    }

    @Test
    void consumerOperationsExposeProtectedRegistrationCurrentAndCancellationContract()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));

        assertThat(map(paths.get(CONSUMER_AVAILABILITY_PATH))).containsOnlyKeys("get");
        assertThat(map(paths.get(CONSUMER_CREATE_PATH))).containsOnlyKeys("post");
        assertThat(map(paths.get(CONSUMER_CURRENT_PATH))).containsOnlyKeys("get");
        assertThat(map(paths.get(CONSUMER_CANCEL_PATH))).containsOnlyKeys("post");

        for (String path : List.of(
                CONSUMER_AVAILABILITY_PATH,
                CONSUMER_CREATE_PATH,
                CONSUMER_CURRENT_PATH,
                CONSUMER_CANCEL_PATH)) {
            Map<String, Object> pathItem = map(paths.get(path));
            Map<String, Object> operation = map(pathItem.values().iterator().next());
            assertThat(list(operation.get("security")))
                    .containsExactly(Map.of("bearerAuth", List.of()));
        }

        Map<String, Object> availability = map(map(paths.get(CONSUMER_AVAILABILITY_PATH)).get("get"));
        assertThat(map(availability.get("responses")).keySet())
                .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "429");

        Map<String, Object> create = map(map(paths.get(CONSUMER_CREATE_PATH)).get("post"));
        assertThat(create)
                .containsEntry("x-runtime-default", "disabled")
                .containsEntry("x-activation-owner-issue", 409)
                .containsEntry("x-location-proof-required", true);
        assertThat(map(create.get("responses")).keySet())
                .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "409", "429");
        assertThat(list(create.get("parameters"))).anySatisfy(parameter ->
                assertThat(map(parameter)).containsEntry("$ref", IDEMPOTENCY_KEY));
        assertThat(map(map(map(create.get("requestBody")).get("content"))
                .get("application/json")))
                .extracting("schema")
                .isEqualTo(Map.of("$ref", "#/components/schemas/WaitingConsumerCreateRequest"));

        Map<String, Object> cancel = map(map(paths.get(CONSUMER_CANCEL_PATH)).get("post"));
        Map<String, Object> current = map(map(paths.get(CONSUMER_CURRENT_PATH)).get("get"));
        assertThat(map(current.get("responses")).keySet())
                .containsExactlyInAnyOrder("200", "401", "403", "404", "429");
        assertThat(map(cancel.get("responses")).keySet())
                .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "409", "429");
        assertThat(list(cancel.get("parameters"))).anySatisfy(parameter ->
                assertThat(map(parameter)).containsEntry("$ref", IDEMPOTENCY_KEY));
        assertThat(map(map(map(cancel.get("requestBody")).get("content"))
                .get("application/json")))
                .extracting("schema")
                .isEqualTo(Map.of("$ref", "#/components/schemas/WaitingTeamTransitionRequest"));

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> createRequest = map(schemas.get("WaitingConsumerCreateRequest"));
        assertThat(list(createRequest.get("required")))
                .containsExactly("businessDate", "partySize");
        assertThat(map(createRequest.get("properties")))
                .containsOnlyKeys("businessDate", "partySize");

        Map<String, Object> snapshot = map(schemas.get("WaitingConsumerSnapshot"));
        assertThat(map(snapshot.get("properties"))).containsOnlyKeys(
                "waitingTeamId", "storeId", "businessDate", "status", "queueSequence",
                "teamsAhead", "partySize", "createdAt", "calledAt", "arrivalDeadline",
                "arrivedAt", "cancelledAt", "version");
        assertThat(snapshot.toString()).doesNotContain(
                "consumerAccountId", "phone", "contact", "audit", "failure");

        assertResponseReference(availability, "404",
                "#/components/responses/WaitingConsumerStoreNotFound");
        assertResponseReference(availability, "403",
                "#/components/responses/WaitingConsumerAccountForbidden");
        assertResponseReference(create, "403",
                "#/components/responses/WaitingConsumerCreateForbidden");
        assertResponseReference(create, "404",
                "#/components/responses/WaitingConsumerStoreNotFound");
        assertResponseReference(create, "409",
                "#/components/responses/WaitingConsumerCreateConflict");
        assertResponseReference(current, "404",
                "#/components/responses/WaitingConsumerTeamNotFound");
        assertResponseReference(current, "403",
                "#/components/responses/WaitingConsumerAccountForbidden");
        assertResponseReference(cancel, "404",
                "#/components/responses/WaitingConsumerTeamNotFound");
        assertResponseReference(cancel, "403",
                "#/components/responses/WaitingConsumerAccountForbidden");
        assertResponseReference(cancel, "409",
                "#/components/responses/WaitingConsumerCancelConflict");

        Map<String, Object> responses = map(map(document.get("components")).get("responses"));
        assertThat(responseExampleCodes(map(responses.get("WaitingConsumerStoreNotFound"))))
                .containsExactly("STORE_001");
        assertThat(responseExampleCodes(map(responses.get("WaitingConsumerTeamNotFound"))))
                .containsExactly("WAITING_003");
        assertThat(responseExampleCodes(map(responses.get("WaitingConsumerAccountForbidden"))))
                .containsExactly("AUTH_011");
        assertThat(responseExampleCodes(map(responses.get("WaitingConsumerCreateForbidden"))))
                .containsExactlyInAnyOrder("AUTH_011", "STORE_015");
        assertThat(responseExampleCodes(map(responses.get("WaitingConsumerCreateConflict"))))
                .containsExactlyInAnyOrder(
                        "STORE_005", "STORE_007", "WAITING_011", "WAITING_012",
                        "COMMON_007", "COMMON_008");
        assertThat(responseExampleCodes(map(responses.get("WaitingConsumerCancelConflict"))))
                .containsExactlyInAnyOrder(
                        "WAITING_005", "WAITING_006", "WAITING_008",
                        "COMMON_007", "COMMON_008");
    }

    @Test
    void waitingLedgerOperationsExposeOnlyTheCanonicalStoreOperatorContract()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));
        assertThat(paths.keySet()).containsAll(LEDGER_OPERATIONS.keySet());

        for (Map.Entry<String, Set<String>> entry : LEDGER_OPERATIONS.entrySet()) {
            Map<String, Object> pathItem = map(paths.get(entry.getKey()));
            assertThat(pathItem.keySet()).containsExactlyInAnyOrderElementsOf(entry.getValue());
            for (String method : entry.getValue()) {
                Map<String, Object> operation = map(pathItem.get(method));
                assertThat(list(operation.get("security")))
                        .containsExactly(Map.of("bearerAuth", List.of()));
                assertThat(map(operation.get("responses")).keySet())
                        .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "409", "429");
                if ("post".equals(method)) {
                    assertThat(list(operation.get("parameters"))).anySatisfy(parameter ->
                            assertThat(map(parameter)).containsEntry("$ref", IDEMPOTENCY_KEY));
                    Map<String, Object> content = map(map(operation.get("requestBody")).get("content"));
                    Map<String, Object> json = map(content.get("application/json"));
                    assertThat(map(json.get("schema")))
                            .containsEntry("$ref", "#/components/schemas/WaitingTeamTransitionRequest");
                } else {
                    assertThat(list(operation.getOrDefault("parameters", List.of()))).noneSatisfy(parameter ->
                            assertThat(map(parameter)).containsEntry("$ref", IDEMPOTENCY_KEY));
                }
            }
        }

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> transitionRequest = map(schemas.get("WaitingTeamTransitionRequest"));
        assertThat(list(transitionRequest.get("required"))).containsExactly("expectedVersion");
        assertThat(map(transitionRequest.get("properties"))).containsOnlyKeys("expectedVersion");

        Map<String, Object> listItem = map(schemas.get("WaitingTeamListItem"));
        Map<String, Object> listItemProperties = map(listItem.get("properties"));
        assertThat(listItemProperties).containsOnlyKeys(
                "waitingTeamId", "status", "queueSequence", "partySize", "createdAt", "version");
        assertThat(listItemProperties).doesNotContainKeys(
                "consumerId", "consumerAccountId", "phone", "phoneNumber", "latitude", "longitude",
                "coordinate", "coordinates", "idempotencyKey");
    }

    @Test
    void waitingLedgerSchemasFixStatesErrorsAndCursorOrdering() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        assertThat(schemas.keySet()).containsAll(Set.of(
                "WaitingTeamStatus",
                "WaitingClosureJobStatus",
                "WaitingTeamPage",
                "WaitingQueueCursor"));

        assertThat(list(map(schemas.get("WaitingTeamStatus")).get("enum")))
                .containsExactly(
                        "WAITING", "CALLED", "ARRIVED", "CHECKED_IN", "CANCELLED", "NO_SHOW",
                        "CLOSED_BY_STORE", "RESERVATION_CONVERTING", "RESERVATION_CONVERTED");
        assertThat(list(map(schemas.get("WaitingClosureJobStatus")).get("enum")))
                .containsExactly("PENDING", "PROCESSING", "COMPLETED", "RECONCILIATION_REQUIRED");

        Map<String, Object> page = map(schemas.get("WaitingTeamPage"));
        assertThat(map(page.get("properties"))).containsOnlyKeys("items", "nextCursor");
        assertThat(map(schemas.get("WaitingQueueCursor")).get("description").toString())
                .contains("queueSequence", "waitingTeamId");

        Map<String, Object> responses = map(map(document.get("components")).get("responses"));
        assertThat(responseExampleCodes(map(responses.get("WaitingLedgerNotFound"))))
                .containsExactlyInAnyOrder("STORE_001", "WAITING_003", "WAITING_004");
        assertThat(responseExampleCodes(map(responses.get("WaitingLedgerConflict"))))
                .containsExactlyInAnyOrder(
                        "WAITING_005", "WAITING_006", "WAITING_007", "WAITING_008", "WAITING_009",
                        "WAITING_010", "COMMON_007", "COMMON_008");
    }

    @Test
    void waitingLedgerConflictDoesNotExposeTheAccountActiveWaitingBusinessRule()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> responses = map(map(document.get("components")).get("responses"));
        Map<String, Object> conflict = map(responses.get("WaitingLedgerConflict"));
        Map<String, Object> json = map(map(conflict.get("content")).get("application/json"));
        Map<String, Object> examples = map(json.get("examples"));

        assertThat(responseExampleCodes(conflict))
                .containsExactlyInAnyOrder(
                        "WAITING_005", "WAITING_006", "WAITING_007", "WAITING_008", "WAITING_009",
                        "WAITING_010", "COMMON_007", "COMMON_008")
                .doesNotContain("WAITING_011");
        assertThat(paths.toString())
                .doesNotContain("WAITING_011", "ACCOUNT_ACTIVE_WAITING_EXISTS");

        Map<String, Object> value = map(
                map(examples.get("activeMembershipConflict")).get("value"));
        assertThat(value)
                .containsOnlyKeys("code", "message")
                .containsEntry("code", "WAITING_008");
    }

    @Test
    void accountWideMembershipMigrationHandoffIsCanonicalAndFailClosed()
            throws IOException {
        String spec = Files.readString(SPEC);

        assertThat(spec).contains(
                "uk_waiting_active_memberships_store_consumer",
                "UNIQUE (consumer_account_id)",
                "SELECT consumer_account_id, COUNT(*) AS active_membership_count",
                "HAVING COUNT(*) > 1",
                "migration과 배포를 차단",
                "자동 취소·삭제·병합하지 않는다",
                "0건을 재확인");
        assertThat(spec).contains(
                "`WAITING_011`은 응답 `code`의 wire 값",
                "`ACCOUNT_ACTIVE_WAITING_EXISTS`는 서버 오류 식별자 이름",
                "`code`, `message` 두 필드만");
    }

    @Test
    void reservationConvertingCanBeCancelledAndCountsAsActive()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> cancel = map(map(paths.get(
                "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/cancellations"))
                .get("post"));

        assertThat(list(cancel.get("x-allowed-source-statuses")))
                .containsExactly("WAITING", "CALLED", "ARRIVED", "RESERVATION_CONVERTING");
        assertThat(cancel).containsEntry("x-result-status", "CANCELLED");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> impact = map(schemas.get("WaitingDisableImpact"));
        Map<String, Object> activeTeamCount = map(map(impact.get("properties")).get("activeTeamCount"));

        assertThat(list(activeTeamCount.get("x-counted-statuses")))
                .containsExactly("WAITING", "CALLED", "ARRIVED", "RESERVATION_CONVERTING");
    }

    @Test
    void waitingSchemasReferenceCanonicalEnumsWithoutInlineCopies() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        assertThat(list(map(schemas.get("WaitingReceptionMode")).get("enum")))
                .containsExactly("AUTO", "MANUAL", "PAUSED");
        assertThat(list(map(schemas.get("WaitingDisableAction")).get("enum")))
                .containsExactly("KEEP_ACTIVE", "CLOSE_ACTIVE_TEAMS");

        Map<String, Object> settingProperties =
                map(map(schemas.get("WaitingSetting")).get("properties"));
        assertThat(map(settingProperties.get("receptionMode")))
                .containsOnlyKeys("$ref")
                .containsEntry("$ref", "#/components/schemas/WaitingReceptionMode");

        Map<String, Object> updateProperties =
                map(map(schemas.get("WaitingSettingUpdateRequest")).get("properties"));
        assertThat(map(updateProperties.get("receptionMode")))
                .containsOnlyKeys("$ref")
                .containsEntry("$ref", "#/components/schemas/WaitingReceptionMode");
        assertThat(map(updateProperties.get("disableAction")))
                .containsOnlyKeys("$ref")
                .containsEntry("$ref", "#/components/schemas/WaitingDisableAction");
    }

    @Test
    void waitingResponsesExposeTheCurrentSettingVersionUnderOneName() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        Map<String, Object> settingProperties =
                map(map(schemas.get("WaitingSetting")).get("properties"));
        Map<String, Object> impactProperties =
                map(map(schemas.get("WaitingDisableImpact")).get("properties"));
        Map<String, Object> impact = map(schemas.get("WaitingDisableImpact"));

        assertThat(settingProperties).containsKey("version");
        assertThat(impactProperties)
                .containsKey("version")
                .doesNotContainKeys("settingVersion", "canCloseActiveTeams");
        assertThat(impactProperties)
                .containsOnlyKeys("storeId", "version", "activeTeamCount");
        assertThat(list(impact.get("required")))
                .containsExactly("storeId", "version", "activeTeamCount");
        assertThat(map(impactProperties.get("version")))
                .containsEntry("type", "integer")
                .containsEntry("format", "int64")
                .containsEntry("minimum", 0);
    }

    @Test
    void requiredWaitingSettingResponseFieldsDoNotDeclareDefaults() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> setting = map(schemas.get("WaitingSetting"));
        Map<String, Object> properties = map(setting.get("properties"));

        assertThat(list(setting.get("required")))
                .contains("enabled", "receptionMode", "advanceOpenMinutes", "version");
        assertThat(List.of("enabled", "receptionMode", "advanceOpenMinutes", "version"))
                .allSatisfy(property ->
                        assertThat(map(properties.get(property))).doesNotContainKey("default"));
    }

    @Test
    void waitingSettingSchemasEnforceEnabledModeAndDisableActionInvariants() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        Map<String, Object> waitingSetting = map(schemas.get("WaitingSetting"));
        assertThat(list(waitingSetting.get("allOf")))
                .hasSize(1)
                .anySatisfy(rule -> assertConditionalConst(
                        map(rule), "enabled", false, "receptionMode", "PAUSED"));

        Map<String, Object> updateRequest = map(schemas.get("WaitingSettingUpdateRequest"));
        List<Object> updateRules = list(updateRequest.get("allOf"));
        assertThat(updateRules)
                .hasSize(2)
                .anySatisfy(rule -> assertConditionalConst(
                        map(rule), "enabled", false, "receptionMode", "PAUSED"))
                .anySatisfy(rule -> {
                    Map<String, Object> condition = map(map(rule).get("if"));
                    assertThat(map(condition.get("properties")))
                            .containsKey("disableAction");
                    assertThat(list(condition.get("required")))
                            .containsExactly("disableAction");
                    Map<String, Object> consequence = map(map(rule).get("then"));
                    Map<String, Object> enabled = map(
                            map(consequence.get("properties")).get("enabled"));
                    assertThat(enabled).containsEntry("const", false);
                });
    }

    @Test
    void waitingOperationsExposeCanonicalValidationAuthorityAndStoreStateFailures()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));

        Map<String, Object> settingsPath = map(paths.get(SETTINGS_PATH));
        assertResponseReference(
                map(settingsPath.get("get")),
                "400",
                "#/components/responses/WaitingStoreIdBadRequest");
        assertResponseReference(
                map(settingsPath.get("get")),
                "409",
                "#/components/responses/WaitingStoreStateConflict");
        assertResponseReference(
                map(map(paths.get(DISABLE_IMPACT_PATH)).get("get")),
                "400",
                "#/components/responses/WaitingStoreIdBadRequest");
        assertResponseReference(
                map(map(paths.get(DISABLE_IMPACT_PATH)).get("get")),
                "409",
                "#/components/responses/WaitingStoreStateConflict");

        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> parameters = map(components.get("parameters"));
        Map<String, Object> storeId = map(parameters.get("StoreId"));
        assertThat(map(storeId.get("schema"))).containsEntry(
                "$ref", "../mvp1-common/openapi.yaml#/components/schemas/PublicId");

        Map<String, Object> responses = map(components.get("responses"));
        assertThat(responseExampleCodes(map(responses.get("WaitingStoreIdBadRequest"))))
                .containsExactly("COMMON_001");
        assertThat(responseExampleCodes(map(responses.get("WaitingStoreForbidden"))))
                .containsExactlyInAnyOrder("AUTH_011", "STORE_003");
        assertThat(responseExampleCodes(map(responses.get("WaitingStoreStateConflict"))))
                .containsExactly("STORE_007");
        assertThat(responseExampleCodes(map(responses.get("WaitingSettingConflict"))))
                .containsExactlyInAnyOrder(
                        "WAITING_001",
                        "WAITING_002",
                        "COMMON_007",
                        "COMMON_008",
                        "STORE_005",
                        "STORE_007");
    }

    private static void assertConditionalConst(
            Map<String, Object> rule,
            String conditionProperty,
            Object conditionValue,
            String consequenceProperty,
            Object consequenceValue
    ) {
        Map<String, Object> condition = map(map(rule.get("if")).get("properties"));
        assertThat(map(condition.get(conditionProperty)))
                .containsEntry("const", conditionValue);
        assertThat(list(map(rule.get("if")).get("required")))
                .containsExactly(conditionProperty);

        Map<String, Object> consequence = map(map(rule.get("then")).get("properties"));
        assertThat(map(consequence.get(consequenceProperty)))
                .containsEntry("const", consequenceValue);
    }

    private static void assertResponseReference(
            Map<String, Object> operation,
            String status,
            String reference
    ) {
        assertThat(map(map(operation.get("responses")).get(status)))
                .containsEntry("$ref", reference);
    }

    private static List<String> responseExampleCodes(Map<String, Object> response) {
        Map<String, Object> json = map(map(response.get("content")).get("application/json"));
        List<String> codes = new ArrayList<>();

        if (json.containsKey("example")) {
            codes.add((String) map(json.get("example")).get("code"));
        }
        if (json.containsKey("examples")) {
            for (Object example : map(json.get("examples")).values()) {
                codes.add((String) map(map(example).get("value")).get("code"));
            }
        }
        return List.copyOf(codes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return (Map<String, Object>) new Yaml().load(input);
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
