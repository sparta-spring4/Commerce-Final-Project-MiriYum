package com.miriyum.domain.store.search.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class StoreSearchOpenApiContractTest {

    private static final String TOO_MANY_REQUESTS_RESPONSE =
            "../mvp1-common/openapi.yaml#/components/responses/TooManyRequests";

    @Test
    void publicRoutesParametersAndResponseFieldsMatchRuntimeContract() throws Exception {
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(Path.of(
                "..", "docs", "specs", "store-search", "openapi.yaml"))) {
            document = new Yaml().load(input);
        }
        Map<String, Object> paths = map(document.get("paths"));
        assertThat(paths).containsKeys(
                "/api/v1/stores",
                "/api/v1/stores/{storeId}",
                "/api/v1/stores/{storeId}/menus");
        assertThat(responseReference(paths, "/api/v1/stores", "429"))
                .isEqualTo(TOO_MANY_REQUESTS_RESPONSE);
        assertThat(responseReference(paths, "/api/v1/stores/{storeId}", "429"))
                .isEqualTo(TOO_MANY_REQUESTS_RESPONSE);
        assertThat(responseReference(paths, "/api/v1/stores/{storeId}/menus", "429"))
                .isEqualTo(TOO_MANY_REQUESTS_RESPONSE);
        assertThat(parameterNames(map(map(paths.get("/api/v1/stores")).get("get"))))
                .contains("serviceDate", "startTime", "partySize", "includesInfants",
                        "availableOnly", "sort", "searchInput", "cursor");
        Map<String, Object> searchOperation = map(map(paths.get("/api/v1/stores")).get("get"));
        List<Map<String, Object>> searchParameters =
                listOfMaps(searchOperation.get("parameters"));
        Map<String, Object> keyword = searchParameters
                .stream().filter(parameter -> "keyword".equals(parameter.get("name")))
                .findFirst().orElseThrow();
        assertThat(map(keyword.get("schema")))
                .containsEntry("minLength", 1)
                .containsEntry("pattern", ".*\\S.*");
        Map<String, Object> searchInput = searchParameters
                .stream().filter(parameter -> "searchInput".equals(parameter.get("name")))
                .findFirst().orElseThrow();
        assertThat(map(searchInput.get("schema")))
                .containsEntry("minLength", 1)
                .containsEntry("maxLength", 100)
                .containsEntry("pattern", ".*\\S.*");
        Map<String, Object> availableOnly = searchParameters.stream()
                .filter(parameter -> "availableOnly".equals(parameter.get("name")))
                .findFirst().orElseThrow();
        assertThat((String) availableOnly.get("description"))
                .contains("5,000");
        Map<String, Object> successResponse = map(
                map(searchOperation.get("responses")).get("200"));
        assertThat((String) successResponse.get("description"))
                .contains("5,000", "totalElements");
        assertThat(parameterNames(map(map(paths.get("/api/v1/stores/{storeId}")).get("get"))))
                .contains("serviceDate", "startTime", "partySize", "includesInfants");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        assertThat(list(map(schemas.get("StoreSummary")).get("required")))
                .containsExactlyInAnyOrder(
                        "storeId", "name", "region", "address", "storeCategoryCode",
                        "operationStatus", "modes", "reservationAvailability");
        assertThat(list(map(schemas.get("PublicMenu")).get("required")))
                .contains("menuId", "name", "description", "price", "representative",
                        "primaryCategoryCode", "secondaryCategoryCodes", "localTags",
                        "holdEnabled", "pickupEnabled", "saleStatus");
        assertThat(list(map(schemas.get("IntegratedStoreSearchData")).get("required")))
                .containsExactlyInAnyOrder(
                        "items", "normalizedCondition", "warnings", "ruleVersion",
                        "vocabularyVersion", "nextCursor");
        assertThat(list(map(schemas.get("IntegratedStoreSearchItem")).get("required")))
                .contains("coordinates", "reservationAvailability");
        assertThat(list(map(schemas.get("InterpretationWarning")).get("required")))
                .containsExactlyInAnyOrder("code", "field");
    }

    private static List<String> parameterNames(Map<String, Object> operation) {
        return listOfMaps(operation.get("parameters")).stream()
                .filter(parameter -> parameter.containsKey("name"))
                .map(parameter -> (String) parameter.get("name"))
                .toList();
    }

    private static String responseReference(Map<String, Object> paths, String path, String responseCode) {
        Map<String, Object> responses = map(map(map(paths.get(path)).get("get")).get("responses"));
        return (String) map(responses.get(responseCode)).get("$ref");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
