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
    private static final Set<String> WAITING_CONSUMER_PATHS = Set.of(
            "/api/v1/consumers/me/stores/{storeId}/waiting-availabilities",
            "/api/v1/consumers/me/stores/{storeId}/waiting-teams",
            "/api/v1/consumers/me/waiting-teams/current",
            "/api/v1/consumers/me/waiting-teams/{waitingTeamId}/cancellations");
    private static final String NOTIFICATION_HISTORY_PATH =
            "/api/v1/consumers/me/notifications";
    private static final String NOTIFICATION_EVENTS_PATH =
            "/api/v1/consumers/me/notification-events";
    private static final String CONSUMER_WAITING_EVENTS_PATH =
            "/api/v1/consumers/me/waiting-events";
    private static final String OPERATOR_WAITING_EVENTS_PATH =
            "/api/v1/store-operators/stores/{storeId}/waiting-events";
    private static final String REPRESENTATIVE_MENUS_PATH =
            "/api/v1/store-operators/stores/{storeId}/representative-menus";
    private static final String STORE_DASHBOARD_ANALYTICS_PATH =
            "/api/v1/store-operators/stores/{storeId}/dashboard-statistics";
    private static final String STORE_RESERVATION_PAYMENT_STATUS_PATH =
            "/api/v1/store-operators/stores/{storeId}/reservations/{reservationId}"
                    + "/payment-status";
    private static final Set<String> POST_MVP1_AUDIENCE_PATHS =
            Stream.concat(
                    Stream.of(
                            MENU_ALTERNATIVE_SEARCH_PATH,
                            NOTIFICATION_HISTORY_PATH,
                            NOTIFICATION_EVENTS_PATH,
                            CONSUMER_WAITING_EVENTS_PATH,
                            OPERATOR_WAITING_EVENTS_PATH,
                            REPRESENTATIVE_MENUS_PATH,
                            STORE_DASHBOARD_ANALYTICS_PATH,
                            STORE_RESERVATION_PAYMENT_STATUS_PATH,
                            "/api/v1/store-operators/stores/{storeId}/images",
                            "/api/v1/store-operators/stores/{storeId}/images/{imageId}",
                            "/api/v1/store-operators/stores/{storeId}/menus/{menuId}/images",
                            "/api/v1/public-files/{imageId}",
                            "/api/v1/consumers/auth/kakao/authorizations",
                            "/api/v1/consumers/auth/kakao/sessions",
                            "/api/v1/consumers/auth/kakao/accounts",
                            "/api/v1/consumers/me/kakao/authorizations",
                            "/api/v1/consumers/me/kakao-links",
                            "/api/v1/store-operators/auth/kakao/authorizations",
                            "/api/v1/store-operators/auth/kakao/sessions",
                            "/api/v1/store-operators/auth/kakao/accounts",
                            "/api/v1/store-operators/me/kakao/authorizations",
                            "/api/v1/store-operators/me/kakao-links",
                            "/api/v1/consumers/account-recovery-verifications",
                            "/api/v1/consumers/account-recovery-cases",
                            "/api/v1/consumers/account-recovery-password-reset-credentials",
                            "/api/v1/consumers/account-recovery-password-resets",
                            "/api/v1/consumers/account-sanction-appeals",
                            "/api/v1/store-operators/account-recovery-verifications",
                            "/api/v1/store-operators/account-recovery-cases",
                            "/api/v1/store-operators/account-recovery-password-reset-credentials",
                            "/api/v1/store-operators/account-recovery-password-resets",
                            "/api/v1/store-operators/account-sanction-appeals",
                            "/api/v1/consumers/me/reservations/{reservationId}/check-in-qr-grants",
                            "/api/v1/store-operators/stores/{storeId}/reservation-check-ins",
                            "/api/v1/store-operators/stores/{storeId}/reservations/"
                                    + "{reservationId}/no-shows"),
                    Stream.concat(
                            PAYMENT_PATHS.stream(),
                            Stream.concat(
                                    RESERVATION_DEPOSIT_PATHS.stream(),
                                    Stream.concat(
                                            WAITING_SETTINGS_PATHS.stream(),
                                            Stream.concat(
                                                    WAITING_LEDGER_PATHS.stream(),
                                                    WAITING_CONSUMER_PATHS.stream())))))
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

    @Test
    void sseContractsAreExposedOnceThroughTheirOwningAudienceEntrypoints()
            throws IOException {
        assertThat(map(paths("consumer-openapi.yaml").get(NOTIFICATION_EVENTS_PATH)))
                .containsExactly(Map.entry(
                        "$ref",
                        "./notification/openapi.yaml#/paths/"
                                + "~1api~1v1~1consumers~1me~1notification-events"));
        assertThat(map(paths("consumer-openapi.yaml").get(CONSUMER_WAITING_EVENTS_PATH)))
                .containsExactly(Map.entry(
                        "$ref",
                        "./waiting/openapi.yaml#/paths/"
                                + "~1api~1v1~1consumers~1me~1waiting-events"));
        assertThat(map(paths("store-operator-openapi.yaml").get(OPERATOR_WAITING_EVENTS_PATH)))
                .containsExactly(Map.entry(
                        "$ref",
                        "./waiting/openapi.yaml#/paths/"
                                + "~1api~1v1~1store-operators~1stores~1{storeId}"
                                + "~1waiting-events"));
    }

    private static void assertPathReferenceResolves(
            String entrypointFile,
            String exposedPath,
            Map<String, Object> pathItem
    ) throws IOException {
        String reference = (String) pathItem.get("$ref");
        int fragmentStart = reference.indexOf('#');
        assertThat(fragmentStart).isPositive();

        String targetFile = reference.substring(0, fragmentStart);
        Path targetContract = SPECS.resolve(entrypointFile)
                .resolveSibling(targetFile)
                .normalize();
        assertThat(targetContract).startsWith(SPECS.normalize());

        String fragment = reference.substring(fragmentStart + 1);
        String pathsPrefix = "/paths/";
        String pathItemsPrefix = "/components/pathItems/";
        if (fragment.startsWith(pathsPrefix)) {
            String targetPath = decodeJsonPointer(fragment.substring(pathsPrefix.length()));
            assertThat(targetPath).isEqualTo(exposedPath);
            assertThat(paths(targetContract)).containsKey(targetPath);
            return;
        }

        assertThat(entrypointFile).isEqualTo("mvp1-openapi.yaml");
        assertThat(exposedPath).isEqualTo("/api/v1/consumers/me/reservations");
        assertThat(fragment).startsWith(pathItemsPrefix);
        String pathItemName = decodeJsonPointer(fragment.substring(pathItemsPrefix.length()));
        assertThat(pathItemName).isEqualTo("Mvp1ConsumerReservations");
        Map<String, Object> targetDocument = document(targetContract);
        assertThat(map(map(targetDocument.get("components")).get("pathItems")))
                .containsKey(pathItemName);
    }

    private static String decodeJsonPointer(String value) {
        return value.replace("~1", "/").replace("~0", "~");
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
        return map(document(file).get("paths"));
    }

    private static Map<String, Object> schemas(String file) throws IOException {
        Map<String, Object> document = document(SPECS.resolve(file));
        return map(map(document.get("components")).get("schemas"));
    }

    private static Map<String, Object> document(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return map(new Yaml().load(input));
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
