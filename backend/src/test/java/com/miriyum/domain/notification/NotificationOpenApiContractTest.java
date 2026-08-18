package com.miriyum.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class NotificationOpenApiContractTest {

    private static final Path CONTRACT = Path.of(
            "..", "docs", "specs", "notification", "openapi.yaml");
    private static final String NOTIFICATION_EVENTS_PATH =
            "/api/v1/consumers/me/notification-events";
    private static final Set<String> WAITING_PURPOSES = Set.of(
            "WAITING_ENTRY_IMMINENT",
            "WAITING_CALLED",
            "WAITING_CANCELLED",
            "WAITING_NO_SHOW",
            "WAITING_CHECKED_IN",
            "WAITING_CLOSED_BY_STORE"
    );
    private static final Set<String> RESERVATION_TERMINAL_PURPOSES = Set.of(
            "RESERVATION_VISIT_COMPLETED",
            "RESERVATION_NO_SHOW"
    );

    @Test
    void reservationTerminalPurposesArePublishedWithoutPaymentOrActionExpansion() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        List<Object> purposes = list(map(schemas.get("NotificationPurpose")).get("enum"));

        assertThat(purposes)
                .containsAll(RESERVATION_TERMINAL_PURPOSES);
        assertThat(list(map(schemas.get("NotificationResourceType")).get("enum")))
                .contains("RESERVATION");

        Map<String, Object> historyItem = map(schemas.get("NotificationHistoryItem"));
        assertThat(historyItem).containsKey("oneOf");
        assertThat(list(historyItem.get("oneOf")))
                .extracting(candidate -> map(candidate).get("$ref"))
                .contains("#/components/schemas/ReservationTerminalNotificationHistoryItem");

        Map<String, Object> terminalItem =
                map(schemas.get("ReservationTerminalNotificationHistoryItem"));
        Map<String, Object> terminalProperties = map(terminalItem.get("properties"));
        assertThat(map(terminalProperties.get("purpose")).get("$ref"))
                .isEqualTo("#/components/schemas/ReservationTerminalNotificationPurpose");
        assertThat(map(terminalProperties.get("action")).get("type"))
                .isEqualTo("null");
    }

    @Test
    void waitingInAppPurposesAndResourceArePublished() throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        assertThat(list(map(schemas.get("NotificationPurpose")).get("enum")))
                .containsAll(WAITING_PURPOSES);
        assertThat(list(map(schemas.get("NotificationResourceType")).get("enum")))
                .contains("WAITING_TEAM");
    }

    @Test
    void notificationChangedSseUsesBearerAndAccountBoundOpaqueCursor()
            throws IOException {
        Map<String, Object> document = load(CONTRACT);
        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemas = map(components.get("schemas"));

        Map<String, Object> path = map(map(document.get("paths")).get(NOTIFICATION_EVENTS_PATH));
        assertThat(path)
                .containsEntry("x-miriyum-runtime-status", "contract-only")
                .containsEntry("x-miriyum-owner-issue", 250);

        Map<String, Object> operation = map(path.get("get"));
        assertThat(list(operation.get("security")))
                .containsExactly(Map.of("bearerAuth", List.of()));
        assertThat(list(operation.get("parameters"))).anySatisfy(parameter ->
                assertThat(map(parameter)).containsEntry(
                        "$ref", "#/components/parameters/NotificationLastEventId"));

        Map<String, Object> responses = map(operation.get("responses"));
        assertThat(responses.keySet())
                .containsExactlyInAnyOrder("200", "400", "401", "403", "429", "503");
        Map<String, Object> stream = map(map(map(responses.get("200")).get("content"))
                .get("text/event-stream"));
        assertThat(map(stream.get("schema")))
                .containsEntry("$ref", "#/components/schemas/NotificationChangedEventStream");

        Map<String, Object> streamSchema = map(schemas.get("NotificationChangedEventStream"));
        assertThat(streamSchema).containsEntry("type", "string");
        assertThat(streamSchema.get("description").toString())
                .contains(
                        "notifications.changed",
                        "Last-Event-ID",
                        "최초 연결·유효한 재연결",
                        "현재 MySQL high-watermark",
                        "한 번",
                        "GET /api/v1/consumers/me/notifications",
                        "keepalive",
                        "PENDING·실패·취소 작업은 신호 대상이 아니다");
        assertThat(streamSchema.get("example").toString())
                .isEqualTo("id: opaque-notification-cursor\n"
                        + "event: notifications.changed\n"
                        + "data: {}\n\n");

        Map<String, Object> lastEventId =
                map(map(components.get("parameters")).get("NotificationLastEventId"));
        assertThat(lastEventId)
                .containsEntry("name", "Last-Event-ID")
                .containsEntry("in", "header")
                .containsEntry("required", false);
        assertThat(lastEventId.get("description").toString())
                .contains("consumer audience", "인증 계정", "계약 version", "최초 연결");
        assertThat(map(lastEventId.get("schema")))
                .containsEntry("minLength", 1)
                .containsEntry("maxLength", 512)
                .containsEntry("pattern", "^[A-Za-z0-9_-]+$");

        assertThat(map(responses.get("400"))).containsEntry(
                "$ref", "#/components/responses/InvalidEventCursor");
        Map<String, Object> invalidCursor =
                map(map(components.get("responses")).get("InvalidEventCursor"));
        assertThat(invalidCursor.get("description").toString())
                .contains("consumer", "계정", "결속");
        Map<String, Object> invalidCursorJson =
                map(map(invalidCursor.get("content")).get("application/json"));
        assertThat(map(invalidCursorJson.get("schema"))).containsEntry(
                "$ref",
                "../mvp1-common/openapi.yaml#/components/schemas/ErrorResponse");
        assertThat(map(invalidCursorJson.get("example")))
                .containsEntry("code", "COMMON_001");
    }

    @Test
    void productionEnumsMatchThePublishedWaitingContract() {
        assertThat(NotificationSourceDomain.valueOf("WAITING"))
                .isEqualTo(NotificationSourceDomain.WAITING);
        assertThat(NotificationResourceType.valueOf("WAITING_TEAM"))
                .isEqualTo(NotificationResourceType.WAITING_TEAM);
        assertThat(java.util.Arrays.stream(NotificationPurpose.values())
                .map(Enum::name))
                .containsAll(WAITING_PURPOSES);
    }

    @SuppressWarnings("unchecked")
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
