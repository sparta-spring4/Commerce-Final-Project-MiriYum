package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AudienceOpenApiContractTest {

    private static final Path SPECS = Path.of("..", "docs", "specs");
    private static final Set<String> NON_FEATURE_OPENAPI_FILES =
            Set.of("mvp1-common/openapi.yaml");
    private static final Set<String> PAYMENT_PATHS = Set.of(
            "/api/v1/consumers/me/payments",
            "/api/v1/consumers/me/payments/{paymentId}",
            "/api/v1/consumers/me/payments/{paymentId}/confirmations",
            "/api/v1/payments/webhooks/portone"
    );
    private static final Set<String> RESERVATION_DEPOSIT_PATHS = Set.of(
            "/api/v1/consumers/me/reservation-requests/{reservationRequestId}",
            "/api/v1/consumers/me/reservation-requests/{reservationRequestId}/finalizations",
            "/api/v1/consumers/me/reservation-requests/{reservationRequestId}/abandonments"
    );
    private static final String MENU_ALTERNATIVE_SEARCH_PATH =
            "/api/v1/stores/{storeId}/menus/{menuId}/alternative-searches";
    private static final Set<String> WAITING_SETTINGS_PATHS = Set.of(
            "/api/v1/store-operators/stores/{storeId}/waiting-settings",
            "/api/v1/store-operators/stores/{storeId}/waiting-settings/deactivation-impact");
    private static final Set<String> WAITING_LEDGER_PATHS = Set.of(
            "/api/v1/store-operators/stores/{storeId}/waiting-teams",
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}",
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/calls",
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/arrivals",
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/check-ins",
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/cancellations",
            "/api/v1/store-operators/stores/{storeId}/waiting-closure-jobs/{jobId}");
    private static final String NOTIFICATION_HISTORY_PATH =
            "/api/v1/consumers/me/notifications";
    private static final String REPRESENTATIVE_MENUS_PATH =
            "/api/v1/store-operators/stores/{storeId}/representative-menus";
    private static final Set<String> POST_MVP1_AUDIENCE_PATHS =
            Stream.concat(
                    Stream.of(
                            MENU_ALTERNATIVE_SEARCH_PATH,
                            NOTIFICATION_HISTORY_PATH,
                            REPRESENTATIVE_MENUS_PATH,
                            "/api/v1/consumers/auth/kakao/authorizations",
                            "/api/v1/consumers/auth/kakao/sessions",
                            "/api/v1/consumers/auth/kakao/accounts",
                            "/api/v1/consumers/me/kakao/authorizations",
                            "/api/v1/consumers/me/kakao-links",
                            "/api/v1/store-operators/auth/kakao/authorizations",
                            "/api/v1/store-operators/auth/kakao/sessions",
                            "/api/v1/store-operators/auth/kakao/accounts",
                            "/api/v1/store-operators/me/kakao/authorizations",
                            "/api/v1/store-operators/me/kakao-links"),
                    Stream.concat(
                            PAYMENT_PATHS.stream(),
                            Stream.concat(
                                    RESERVATION_DEPOSIT_PATHS.stream(),
                                    Stream.concat(
                                            WAITING_SETTINGS_PATHS.stream(),
                                            WAITING_LEDGER_PATHS.stream()))))
                    .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> LEGACY_PREFIXES = Set.of(
            "/api/v1/consumer-auth",
            "/api/v1/consumer-accounts",
            "/api/v1/store-operator-auth",
            "/api/v1/store-operator-accounts",
            "/api/v1/store-operator/",
            "/api/v1/reservations",
            "/api/v1/pickup-reservations");

    @Test
    void audienceEntrypointsMatchTheMvp1AggregateAndLaterStagePaths() throws IOException {
        Set<String> publicPaths = paths("public-openapi.yaml").keySet();
        Set<String> consumerPaths = paths("consumer-openapi.yaml").keySet();
        Set<String> operatorPaths = paths("store-operator-openapi.yaml").keySet();
        Set<String> platformOperatorPaths = paths("platform-operator-openapi.yaml").keySet();
        Set<String> aggregatePaths = paths("mvp1-openapi.yaml").keySet();

        assertThat(consumerPaths)
                .isNotEmpty()
                .allMatch(path -> path.startsWith("/api/v1/consumers/"));
        assertThat(operatorPaths)
                .isNotEmpty()
                .allMatch(path -> path.startsWith("/api/v1/store-operators/"));
        assertThat(intersection(publicPaths, consumerPaths)).isEmpty();
        assertThat(intersection(publicPaths, operatorPaths)).isEmpty();
        assertThat(intersection(consumerPaths, operatorPaths)).isEmpty();
        assertThat(platformOperatorPaths).allMatch(path -> path.startsWith("/api/v1/platform-operators/"));
        assertThat(intersection(publicPaths, platformOperatorPaths)).isEmpty();
        assertThat(intersection(consumerPaths, platformOperatorPaths)).isEmpty();
        assertThat(intersection(operatorPaths, platformOperatorPaths)).isEmpty();

        Set<String> allAudiencePaths = new HashSet<>(publicPaths);
        allAudiencePaths.addAll(consumerPaths);
        allAudiencePaths.addAll(operatorPaths);
        allAudiencePaths.addAll(platformOperatorPaths);
        assertThat(intersection(aggregatePaths, POST_MVP1_AUDIENCE_PATHS)).isEmpty();
        assertThat(intersection(aggregatePaths, platformOperatorPaths)).isEmpty();
        Set<String> mvp1AndLaterStagePaths = new HashSet<>(aggregatePaths);
        mvp1AndLaterStagePaths.addAll(POST_MVP1_AUDIENCE_PATHS);
        mvp1AndLaterStagePaths.addAll(platformOperatorPaths);
        assertThat(mvp1AndLaterStagePaths).isEqualTo(allAudiencePaths);
    }

    @Test
    void audienceEntrypointsExposeEveryFeaturePathUnlessExplicitlyExcluded() throws IOException {
        Set<String> featurePaths = new HashSet<>();
        for (String file : featureOpenApiFiles()) {
            featurePaths.addAll(paths(file).keySet());
        }

        Set<String> audiencePaths = new HashSet<>(paths("public-openapi.yaml").keySet());
        audiencePaths.addAll(paths("consumer-openapi.yaml").keySet());
        audiencePaths.addAll(paths("store-operator-openapi.yaml").keySet());
        audiencePaths.addAll(paths("platform-operator-openapi.yaml").keySet());

        assertThat(audiencePaths).isEqualTo(featurePaths);
    }

    @Test
    void kakaoLoginDataExposesAuthenticationAndSignUpFieldsAtTheTopLevel() throws IOException {
        Map<String, Object> schemas = schemas("auth-account/openapi.yaml");
        Map<String, Object> kakaoLoginData = map(schemas.get("KakaoLoginData"));
        Map<String, Object> properties = map(kakaoLoginData.get("properties"));

        assertThat(properties).containsKeys("status", "accessToken", "tokenType", "expiresIn", "signUpTicket");
        assertThat(map(properties.get("status"))).doesNotContainKeys(
                "accessToken", "tokenType", "expiresIn", "signUpTicket");
    }

    @Test
    void entrypointPathItemsAreSingleReferencesWithoutLegacyUrls() throws IOException {
        for (String file : Set.of(
                "public-openapi.yaml",
                "consumer-openapi.yaml",
                "store-operator-openapi.yaml",
                "platform-operator-openapi.yaml",
                "mvp1-openapi.yaml")) {
            Map<String, Object> paths = paths(file);
            assertThat(paths).isNotEmpty();
            assertThat(paths.keySet())
                    .noneMatch(path -> LEGACY_PREFIXES.stream().anyMatch(path::startsWith));
            assertThat(paths.values())
                    .allSatisfy(item -> assertThat(map(item))
                            .containsOnlyKeys("$ref"));
            for (Map.Entry<String, Object> entry : paths.entrySet()) {
                assertPathReferenceResolves(file, entry.getKey(), map(entry.getValue()));
            }
        }
    }

    private static void assertPathReferenceResolves(
            String entrypointFile,
            String exposedPath,
            Map<String, Object> pathItem
    ) throws IOException {
        String reference = (String) pathItem.get("$ref");
        String pathFragmentPrefix = "#/paths/";
        int fragmentStart = reference.indexOf(pathFragmentPrefix);
        assertThat(fragmentStart).isPositive();

        String targetFile = reference.substring(0, fragmentStart);
        String escapedTargetPath = reference.substring(
                fragmentStart + pathFragmentPrefix.length());
        String targetPath = escapedTargetPath.replace("~1", "/").replace("~0", "~");
        assertThat(targetPath).isEqualTo(exposedPath);

        Path targetContract = SPECS.resolve(entrypointFile)
                .resolveSibling(targetFile)
                .normalize();
        assertThat(targetContract).startsWith(SPECS.normalize());
        assertThat(paths(targetContract)).containsKey(targetPath);
    }

    @Test
    void notificationHistoryExposesOnlyDeliveredInAppRecordsAndPickupPurposes() throws IOException {
        Map<String, Object> schemas = schemas("notification/openapi.yaml");
        Map<String, Object> historyItem = map(schemas.get("NotificationHistoryItem"));
        Map<String, Object> properties = map(historyItem.get("properties"));
        Set<Object> required = Set.copyOf(list(historyItem.get("required")));

        assertThat(schemas).doesNotContainKey("NotificationDeliveryStatus");
        assertThat(properties).doesNotContainKey("deliveryStatus");
        assertThat(required).contains("deliveredAt").doesNotContain("deliveryStatus");
        assertThat(map(properties.get("deliveredAt")))
                .containsEntry(
                        "$ref",
                        "../mvp1-common/openapi.yaml#/components/schemas/OffsetDateTime")
                .doesNotContainKey("oneOf");

        assertThat(list(map(schemas.get("NotificationPurpose")).get("enum")))
                .contains("PICKUP_RESERVATION_CONFIRMED", "PICKUP_RESERVATION_CANCELLED");
    }

    @Test
    void notificationActionsBindEachActionTypeToItsOnlyResourceType() throws IOException {
        Map<String, Object> schemas = schemas("notification/openapi.yaml");
        Map<String, Object> action = map(schemas.get("NotificationAction"));

        assertThat(list(action.get("oneOf")))
                .extracting(item -> map(item).get("$ref"))
                .containsExactlyInAnyOrder(
                        "#/components/schemas/ReservationDetailNotificationAction",
                        "#/components/schemas/PickupReservationDetailNotificationAction",
                        "#/components/schemas/MenuSubstitutionReviewNotificationAction");

        assertActionResourcePair(
                schemas,
                "ReservationDetailNotificationAction",
                "RESERVATION_DETAIL",
                "RESERVATION");
        assertActionResourcePair(
                schemas,
                "PickupReservationDetailNotificationAction",
                "PICKUP_RESERVATION_DETAIL",
                "PICKUP_RESERVATION");
        assertActionResourcePair(
                schemas,
                "MenuSubstitutionReviewNotificationAction",
                "MENU_SUBSTITUTION_REVIEW",
                "MENU_SUBSTITUTION_PROPOSAL");
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static Set<String> featureOpenApiFiles() throws IOException {
        try (var entries = Files.list(SPECS)) {
            return entries
                    .filter(Files::isDirectory)
                    .map(directory -> directory.resolve("openapi.yaml"))
                    .filter(Files::isRegularFile)
                    .map(SPECS::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(file -> !NON_FEATURE_OPENAPI_FILES.contains(file))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    private static Map<String, Object> paths(String file) throws IOException {
        return paths(SPECS.resolve(file));
    }

    private static Map<String, Object> paths(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return map(map(new Yaml().load(input)).get("paths"));
        }
    }

    private static Map<String, Object> schemas(String file) throws IOException {
        try (InputStream input = Files.newInputStream(SPECS.resolve(file))) {
            Map<String, Object> document = map(new Yaml().load(input));
            return map(map(document.get("components")).get("schemas"));
        }
    }

    private static void assertActionResourcePair(
            Map<String, Object> schemas,
            String schemaName,
            String actionType,
            String resourceType) {
        Map<String, Object> actionSchema = map(schemas.get(schemaName));
        Map<String, Object> actionProperties = map(actionSchema.get("properties"));
        assertThat(actionSchema).containsEntry("additionalProperties", false);
        assertThat(list(actionSchema.get("required")))
                .containsExactlyInAnyOrder("type", "resource", "availability", "expiresAt");
        assertThat(map(actionProperties.get("type"))).containsEntry("const", actionType);

        Map<String, Object> resourceSchema = map(actionProperties.get("resource"));
        Map<String, Object> resourceProperties = map(resourceSchema.get("properties"));
        assertThat(resourceSchema).containsEntry("additionalProperties", false);
        assertThat(list(resourceSchema.get("required")))
                .containsExactlyInAnyOrder("type", "id");
        assertThat(map(resourceProperties.get("type"))).containsEntry("const", resourceType);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> list(Object value) {
        return (java.util.List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
