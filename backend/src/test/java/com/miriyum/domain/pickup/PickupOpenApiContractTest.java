package com.miriyum.domain.pickup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PickupOpenApiContractTest {

    private static final Path CONTRACT = Path.of(
            "..", "docs", "specs", "menu-hold-pickup", "openapi.yaml");

    @Test
    void pickupRoutesAndSchemasKeepTheApprovedPublicShape() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));
        assertThat(paths).containsKeys(
                "/api/v1/stores/{storeId}/pickup-availability",
                "/api/v1/consumers/me/pickup-reservations",
                "/api/v1/consumers/me/pickup-reservations/{pickupReservationId}",
                "/api/v1/consumers/me/pickup-reservations/{pickupReservationId}/cancellations",
                "/api/v1/store-operators/stores/{storeId}/pickup-reservations",
                "/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}",
                "/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}/fulfillments",
                "/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}/cancellations");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> create = map(schemas.get("PickupReservationCreateRequest"));
        assertThat(list(create.get("required")))
                .containsExactly("storeId", "pickupDate", "pickupTime", "menuSelections");
        assertThat(map(create.get("properties")))
                .containsKeys("storeId", "pickupDate", "pickupTime", "menuSelections")
                .doesNotContainKeys("endTime", "businessType", "partySize");
        Map<String, Object> menuSelections = map(
                map(create.get("properties")).get("menuSelections"));
        assertThat(menuSelections).containsKey("description");
        assertThat(menuSelections.get("description").toString())
                .contains("동일 menuId", "합산", "100");
        assertThat(list(map(schemas.get("PickupStatus")).get("enum")))
                .containsExactly("CONFIRMED", "PICKED_UP", "CANCELLED");
    }

    @Test
    void creationDocumentsApprovedStoreAndPickupErrors() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> operation = map(map(paths.get(
                "/api/v1/consumers/me/pickup-reservations")).get("post"));
        Map<String, Object> operationResponses = map(operation.get("responses"));
        assertThat(map(operationResponses.get("404"))).containsEntry(
                "$ref", "#/components/responses/PickupCreationNotFound");
        assertThat(map(operationResponses.get("409"))).containsEntry(
                "$ref", "#/components/responses/PickupConflict");

        Map<String, Object> responses = map(map(document.get("components")).get("responses"));
        assertThat(responses.get("PickupCreationNotFound").toString())
                .contains("STORE_001", "STORE_009");
        assertThat(responses.get("PickupConflict").toString())
                .contains("STORE_010", "PICKUP_002", "PICKUP_003", "PICKUP_004");
    }

    @Test
    void cancellationReasonsRejectWhitespaceOnlyAndAllowNonBlankMultilineText()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        assertCancellationReasonPattern(schemas, "PickupCancellationRequest");
        assertCancellationReasonPattern(schemas, "StorePickupCancellationRequest");
    }

    @Test
    void pickupMutationConflictsDocumentRetryExhaustion() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> responses = map(map(document.get("components")).get("responses"));

        assertThat(responses.get("PickupConflict").toString()).contains("COMMON_008");
        assertThat(responses.get("PickupCancellationConflict").toString())
                .contains("COMMON_008");
        assertThat(responses.get("PickupStateConflict").toString()).contains("COMMON_008");
    }

    @Test
    void cancellationAndOperatorRoutesDocumentAllRuntimeNotFoundAndConflictErrors()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> paths = map(document.get("paths"));

        Map<String, Object> consumerCancellation = map(map(paths.get(
                "/api/v1/consumers/me/pickup-reservations/{pickupReservationId}/cancellations"))
                .get("post"));
        assertThat(map(map(consumerCancellation.get("responses")).get("409")))
                .containsEntry("$ref", "#/components/responses/PickupCancellationConflict");

        Map<String, Object> operatorList = map(map(paths.get(
                "/api/v1/store-operators/stores/{storeId}/pickup-reservations"))
                .get("get"));
        assertThat(map(map(operatorList.get("responses")).get("404")))
                .containsEntry("$ref", "#/components/responses/StoreNotFound");

        assertOperatorReservationNotFoundResponse(
                paths,
                "/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}",
                "get");
        assertOperatorReservationNotFoundResponse(
                paths,
                "/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}/fulfillments",
                "post");
        assertOperatorStateConflictResponse(
                paths,
                "/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}/fulfillments");
        assertOperatorReservationNotFoundResponse(
                paths,
                "/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}/cancellations",
                "post");
        assertOperatorStateConflictResponse(
                paths,
                "/api/v1/store-operators/stores/{storeId}/pickup-reservations/{pickupReservationId}/cancellations");

        Map<String, Object> responses = map(map(document.get("components")).get("responses"));
        assertThat(responses.get("PickupCancellationConflict").toString())
                .contains("PICKUP_005", "PICKUP_006");
        assertThat(responses.get("PickupStateConflict").toString())
                .contains("PICKUP_005")
                .doesNotContain("PICKUP_006");
        assertThat(responses.get("StorePickupNotFound").toString())
                .contains("STORE_001", "PICKUP_001");
    }

    @Test
    void canonicalPolicyPinsAcquireRestoreAndCancellationCutoff() throws IOException {
        String spec = Files.readString(Path.of(
                "..", "docs", "specs", "menu-hold-pickup", "spec.md"));
        assertThat(spec)
                .contains("`acquireOperationId`")
                .contains("`sourceAcquireOperationId`")
                .contains("저장된 `pickupAt`보다 이른 동안만 취소")
                .contains("정확히 같은 시각부터는 `PICKUP_006`");
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

    private static void assertOperatorReservationNotFoundResponse(
            Map<String, Object> paths,
            String path,
            String method
    ) {
        Map<String, Object> operation = map(map(paths.get(path)).get(method));
        assertThat(map(map(operation.get("responses")).get("404")))
                .containsEntry("$ref", "#/components/responses/StorePickupNotFound");
    }

    private static void assertOperatorStateConflictResponse(
            Map<String, Object> paths,
            String path
    ) {
        Map<String, Object> operation = map(map(paths.get(path)).get("post"));
        assertThat(map(map(operation.get("responses")).get("409")))
                .containsEntry("$ref", "#/components/responses/PickupStateConflict");
    }

    private static void assertCancellationReasonPattern(
            Map<String, Object> schemas,
            String schemaName
    ) {
        Map<String, Object> schema = map(schemas.get(schemaName));
        Map<String, Object> reason = map(map(schema.get("properties")).get("reason"));
        assertThat(reason).containsKey("pattern");

        Pattern pattern = Pattern.compile(reason.get("pattern").toString());
        assertThat(pattern.matcher(" ").matches()).isFalse();
        assertThat(pattern.matcher("재료 소진").matches()).isTrue();
        assertThat(pattern.matcher("내용\n추가").matches()).isTrue();
    }
}
